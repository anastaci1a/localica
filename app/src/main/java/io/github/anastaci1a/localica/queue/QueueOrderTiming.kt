package io.github.anastaci1a.localica.queue

/**
 * Aggregate diagnostics for one accepted order operation. The caller serializes access, marks
 * completed boundaries and consumes [finish]'s report; this owner never invokes application code
 * other than the supplied clock. It does not schedule work or change completion order.
 *
 * Clock readings are monotonic nanoseconds with an arbitrary (possibly negative) origin. Signed
 * nanoTime wraparound is supported for operations shorter than Long.MAX_VALUE nanoseconds. A
 * backward reading or clock exception disables further timing, retaining only valid observations.
 */
internal class QueueOrderTiming(private val clockNanos: () -> Long) {
    enum class Stage {
        Accepted, ActorStarted, CursorCaptured, DurableEditSaved, FreshSnapshot,
        GrantsTemplates, MainNativeApiReturned, CallbackCompleted,
    }

    enum class Outcome { Success, Failure, Cancelled }

    /** Null means unobserved, not zero. Template counts are totals, not per-entry records. */
    data class Counts(
        val queueEntries: Int? = null,
        val libraryTracks: Int? = null,
        val templatesReused: Int? = null,
        val templatesBuilt: Int? = null,
    )

    /** Scalar-only values keep a terminal report immutable, including on the JVM. */
    data class Milestones(
        val acceptedNanos: Long? = null,
        val actorStartedNanos: Long? = null,
        val cursorCapturedNanos: Long? = null,
        val durableEditSavedNanos: Long? = null,
        val freshSnapshotNanos: Long? = null,
        val grantsTemplatesNanos: Long? = null,
        val mainNativeApiReturnedNanos: Long? = null,
        val callbackCompletedNanos: Long? = null,
    ) {
        internal fun at(stage: Stage): Long? = when (stage) {
            Stage.Accepted -> acceptedNanos
            Stage.ActorStarted -> actorStartedNanos
            Stage.CursorCaptured -> cursorCapturedNanos
            Stage.DurableEditSaved -> durableEditSavedNanos
            Stage.FreshSnapshot -> freshSnapshotNanos
            Stage.GrantsTemplates -> grantsTemplatesNanos
            Stage.MainNativeApiReturned -> mainNativeApiReturnedNanos
            Stage.CallbackCompleted -> callbackCompletedNanos
        }

        internal fun with(stage: Stage, elapsedNanos: Long): Milestones = when (stage) {
            Stage.Accepted -> copy(acceptedNanos = elapsedNanos)
            Stage.ActorStarted -> copy(actorStartedNanos = elapsedNanos)
            Stage.CursorCaptured -> copy(cursorCapturedNanos = elapsedNanos)
            Stage.DurableEditSaved -> copy(durableEditSavedNanos = elapsedNanos)
            Stage.FreshSnapshot -> copy(freshSnapshotNanos = elapsedNanos)
            Stage.GrantsTemplates -> copy(grantsTemplatesNanos = elapsedNanos)
            Stage.MainNativeApiReturned -> copy(mainNativeApiReturnedNanos = elapsedNanos)
            Stage.CallbackCompleted -> copy(callbackCompletedNanos = elapsedNanos)
        }
    }

    data class Report(
        val outcome: Outcome,
        val milestones: Milestones,
        val counts: Counts,
        val totalNanos: Long?,
        val clockValid: Boolean,
    ) {
        /** Elapsed from acceptance; native API return does not imply decoder readiness. */
        fun elapsedNanos(stage: Stage): Long? = milestones.at(stage)

        /** Missing endpoints stay absent; skipped phases are never fabricated as zero. */
        fun durationNanos(from: Stage, through: Stage): Long? {
            if (through.ordinal < from.ordinal) return null
            val start = elapsedNanos(from) ?: return null
            val end = elapsedNanos(through) ?: return null
            return end - start
        }
    }

    private var clockValid = true
    private val acceptedAt = readClock()
    private var lastElapsed = 0L
    private var lastStage = Stage.Accepted
    private var milestones = Milestones(acceptedNanos = acceptedAt?.let { 0L })
    private var counts = Counts()
    private var finished = false

    /** Optional stages may be skipped. Repeated, backward and post-terminal marks are ignored. */
    fun mark(stage: Stage): Boolean {
        if (finished || stage.ordinal <= lastStage.ordinal) return false
        val elapsed = readElapsed() ?: return false
        milestones = milestones.with(stage, elapsed)
        lastStage = stage
        return true
    }

    /**
     * Merge observed aggregate totals, leaving null fields unchanged. Negative inputs reject the
     * entire update without throwing or changing earlier counts. Counts are not inferred from time.
     */
    fun recordCounts(queueEntries: Int? = null, libraryTracks: Int? = null,
        templatesReused: Int? = null, templatesBuilt: Int? = null): Boolean {
        if (finished || queueEntries != null && queueEntries < 0 ||
            libraryTracks != null && libraryTracks < 0 ||
            templatesReused != null && templatesReused < 0 ||
            templatesBuilt != null && templatesBuilt < 0) return false
        counts = Counts(queueEntries ?: counts.queueEntries, libraryTracks ?: counts.libraryTracks,
            templatesReused ?: counts.templatesReused, templatesBuilt ?: counts.templatesBuilt)
        return true
    }

    /** Returns one terminal snapshot, then null. Does not imply any unmarked phase completed. */
    fun finish(outcome: Outcome): Report? {
        if (finished) return null
        finished = true
        val total = readElapsed()
        return Report(outcome, milestones, counts, total, clockValid)
    }

    private fun readElapsed(): Long? {
        val origin = acceptedAt ?: return null
        val now = readClock() ?: return null
        val elapsed = now - origin
        if (elapsed < lastElapsed) {
            clockValid = false
            return null
        }
        lastElapsed = elapsed
        return elapsed
    }

    private fun readClock(): Long? {
        if (!clockValid) return null
        return try { clockNanos() } catch (_: Exception) {
            clockValid = false
            null
        }
    }
}
