package io.github.anastaci1a.localica

import java.util.Collections
import java.util.EnumSet

/**
 * Admission of an already-attached, single-Window protocol observation only.
 * This does not certify a complete frame population, performance, launch or
 * presentation. A retained post-end callback cannot prove native-tail delivery.
 * Source/APK/device/installation identities belong to the external run receipt;
 * the phase evidence below is supplied by the caller, not inferred from a waiter.
 */
internal object NavigationFrameDiagnosticWindow {
    enum class Clock { SYSTEM_NANOTIME_VSYNC, UNKNOWN }
    enum class ObservationStatus { INCOMPLETE_RECEIVED_SUBSET_PROTOCOL_OBSERVED, REJECTED }
    enum class Rejection {
        UNKNOWN_CLOCK, INVALID_PHASE_ORDER, PROTOCOL_EXCEPTION, TEARDOWN_EXCEPTION,
        POST_END_DRAWS_UNFINISHED, WAITER_UNSUCCESSFUL, MISSING_RESULT, MISSING_SNAPSHOT,
        SNAPSHOT_BOUNDS_MISMATCH, UNEXPECTED_SOURCE_TAIL, EMPTY_IN_WINDOW_SUBSET,
        INVALID_FRAME_CLOCK, MISSING_RETAINED_END_WITNESS, LISTENER_REPORT_LOSS,
        BUFFER_OVERFLOW, CALLBACKS_REJECTED, INVALID_CALLBACKS, UNRECORDED_LISTENER_DROPS,
        LISTENER_REMOVAL_UNCONFIRMED, HANDLER_TERMINAL_UNREACHED, WORKER_NOT_STOPPED,
        INVALID_COUNTERS
    }

    /**
     * Fixed script: eight requested View draws, then a two-View-draw post-end request.
     * Incidental Window frames remain possible; this is not an exact native frame inventory.
     */
    const val DRAW_PROTOCOL = "attached-view-8-draws/frozen-end/2-post-end-animation-draws-v1"
    const val WAIT_TIMEOUT_MILLIS = 5000L // Liveness bound, never a performance budget.

    data class Protocol(
        val clock: Clock,
        val registrationCompletedNanos: Long,
        val startInclusiveNanos: Long,
        val endExclusiveNanos: Long,
        val postEndDrawRequestedNanos: Long?,
        val postEndDrawsCompleted: Boolean,
        val waiterSucceeded: Boolean
    ) {
        val drawProtocol: String get() = DRAW_PROTOCOL
        val waitTimeoutMillis: Long get() = WAIT_TIMEOUT_MILLIS
    }

    /** All denominators count retained callback records, including timestamp duplicates. */
    data class RetainedSubsetCounts(
        val beforeWindow: Int,
        val inWindow: Int,
        val afterWindow: Int,
        val lateInWindow: Int,
        val duplicateTimestampRecords: Int,
        val deadlineMetricsSupported: Boolean,
        val deadlineEligibleInWindow: Int,
        val deadlineUnavailableInWindow: Int,
        val durationExceedsReportedDeadlineInWindow: Int
    ) {
        // Null is unavailable, never a fabricated zero or refresh-derived deadline.
        // Retained-subset arithmetic remains loss-affected on rejected receipts.
        // This is NOT a jank/display-loss rate, even when the protocol is observed.
        val durationExceedsReportedDeadlineFraction: Double? get() =
            if (deadlineEligibleInWindow == 0) null
            else durationExceedsReportedDeadlineInWindow.toDouble() / deadlineEligibleInWindow
    }

    class Receipt internal constructor(
        val protocol: Protocol,
        val recorderResult: NavigationFrameMetricsRecorder.Result?,
        val protocolFailure: Throwable?,
        val teardownFailure: Throwable?,
        rejections: Set<Rejection>,
        val retainedSubsetCounts: RetainedSubsetCounts?,
        val retainedEndWitness: NavigationFrameWindow.Observation?
    ) {
        val rejections: Set<Rejection> = Collections.unmodifiableSet(
            EnumSet.noneOf(Rejection::class.java).also { it.addAll(rejections) })
        val observationStatus: ObservationStatus get() = if (rejections.isEmpty())
            ObservationStatus.INCOMPLETE_RECEIVED_SUBSET_PROTOCOL_OBSERVED else ObservationStatus.REJECTED
        val tailStatus: NavigationFrameWindow.TailStatus get() = NavigationFrameWindow.TailStatus.INCOMPLETE
        val completeWindowMeasurement: Boolean get() = false
        // Preserve both original exceptions without mutating/suppressing either in a pure predicate.
        // The lifecycle owner must rethrow the body first, suppressing distinct teardown failure.
        val primaryFailure: Throwable? get() = protocolFailure ?: teardownFailure
    }

    fun inspect(
        protocol: Protocol,
        result: NavigationFrameMetricsRecorder.Result?,
        protocolFailure: Throwable? = null,
        teardownFailure: Throwable? = null
    ): Receipt {
        val rejected = EnumSet.noneOf(Rejection::class.java)
        fun reject(condition: Boolean, reason: Rejection) { if (condition) rejected.add(reason) }
        reject(protocol.clock != Clock.SYSTEM_NANOTIME_VSYNC, Rejection.UNKNOWN_CLOCK)
        reject(protocol.registrationCompletedNanos > protocol.startInclusiveNanos ||
            protocol.startInclusiveNanos >= protocol.endExclusiveNanos ||
            protocol.postEndDrawRequestedNanos == null ||
            protocol.postEndDrawRequestedNanos < protocol.endExclusiveNanos, Rejection.INVALID_PHASE_ORDER)
        reject(protocolFailure != null, Rejection.PROTOCOL_EXCEPTION)
        reject(teardownFailure != null, Rejection.TEARDOWN_EXCEPTION)
        reject(!protocol.postEndDrawsCompleted, Rejection.POST_END_DRAWS_UNFINISHED)
        reject(!protocol.waiterSucceeded, Rejection.WAITER_UNSUCCESSFUL)
        reject(result == null, Rejection.MISSING_RESULT)
        result?.let {
            reject(!it.listenerRemovalConfirmed, Rejection.LISTENER_REMOVAL_UNCONFIRMED)
            reject(!it.handlerTerminalReached, Rejection.HANDLER_TERMINAL_UNREACHED)
            reject(!it.workerStopped, Rejection.WORKER_NOT_STOPPED)
            reject(it.callbacksRejectedAtReturn != 0L, Rejection.CALLBACKS_REJECTED)
            reject(it.invalidCallbacksAtReturn != 0L, Rejection.INVALID_CALLBACKS)
            reject(it.listenerDropsNotRecordedAtReturn != 0L, Rejection.UNRECORDED_LISTENER_DROPS)
            reject(it.callbacksRejectedAtReturn < 0L || it.invalidCallbacksAtReturn < 0L ||
                it.listenerDropsNotRecordedAtReturn < 0L, Rejection.INVALID_COUNTERS)
        }
        val snapshot = result?.snapshot
        reject(snapshot == null, Rejection.MISSING_SNAPSHOT)
        var counts: RetainedSubsetCounts? = null
        var witness: NavigationFrameWindow.Observation? = null
        if (snapshot != null) {
            reject(snapshot.startInclusiveNanos != protocol.startInclusiveNanos ||
                snapshot.endExclusiveNanos != protocol.endExclusiveNanos, Rejection.SNAPSHOT_BOUNDS_MISMATCH)
            reject(snapshot.tailStatus != NavigationFrameWindow.TailStatus.INCOMPLETE, Rejection.UNEXPECTED_SOURCE_TAIL)
            reject(snapshot.inWindowCount == 0, Rejection.EMPTY_IN_WINDOW_SUBSET)
            reject(snapshot.reportedListenerDropCount != 0L, Rejection.LISTENER_REPORT_LOSS)
            reject(snapshot.bufferOverflowCount != 0L, Rejection.BUFFER_OVERFLOW)
            var eligible = 0
            var exceeds = 0
            var duplicates = 0
            val timestamps = HashSet<Long>()
            for (index in 0 until snapshot.storedCount) {
                val frame = snapshot[index]
                // Do not require timestamp sorting: native records may be duplicated/out of order.
                reject(frame.frameTimestampNanos == -1L ||
                    frame.callbackArrivalNanos < frame.frameTimestampNanos, Rejection.INVALID_FRAME_CLOCK)
                if (!timestamps.add(frame.frameTimestampNanos)) duplicates++
                if (witness == null && frame.frameTimestampNanos >= protocol.endExclusiveNanos) witness = frame
                if (frame.position == NavigationFrameWindow.Position.IN_WINDOW && frame.deadlineDurationNanos != null) {
                    eligible++
                    if (frame.exceedsReportedDeadline == true) exceeds++
                }
            }
            counts = RetainedSubsetCounts(snapshot.beforeWindowCount, snapshot.inWindowCount,
                snapshot.afterWindowCount, snapshot.lateInWindowCount, duplicates,
                snapshot.deadlineMetricsSupported, eligible, snapshot.inWindowCount - eligible, exceeds)
        }
        reject(witness == null, Rejection.MISSING_RETAINED_END_WITNESS)
        return Receipt(protocol, result, protocolFailure, teardownFailure, rejected, counts, witness)
    }
}
