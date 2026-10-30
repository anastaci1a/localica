package io.github.anastaci1a.localica

import android.os.Build
import android.os.Looper
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/** Received native subset only. Main publishes identity; the creating test thread owns all lifecycle calls. */
internal class PopupFrameWorkRecorder(private val testId: String) {
    private class Owner(val id: Int, val window: Window, val discovered: Long) {
        var recorder: NavigationFrameMetricsRecorder? = null
        var request: Long? = null
        var returned: Long? = null
        var end: Long? = null
        var finishReturned: Long? = null
        var result: NavigationFrameMetricsRecorder.Result? = null
        var attempted = false
        var finished = false
        var emergencyCloseAttempted = false
        val errors = mutableListOf<String>()
    }
    private val control = Thread.currentThread()
    private val gate = Any()
    private val started = NavigationFrameMetricsRecorder.markNanos()
    private val identityCapacity = 16
    private val eventCapacity = 8192
    private val identities = IdentityHashMap<Window, Owner>()
    private val owners = mutableListOf<Owner>()
    private val events = mutableListOf<JSONObject>()
    private var latest: Owner? = null
    private var active: Owner? = null // Control thread only.
    private var attempts = 0L
    private var droppedEvents = 0L
    private var nullHandoffs = 0L
    private var overflowHandoffs = 0L
    private var sealed = false
    private var failure: Throwable? = null // Control thread only; deferred until body cleanup.
    private var finalizedOwners: List<Owner>? = null
    private var finalizedEnd: Long? = null
    private var exportAttempted = false

    /** No blocking lifecycle, Snapshot access, file IO or control-thread work while holding gate. */
    fun publish(window: Window?) = synchronized(gate) {
        if (!sealed) {
            if (window == null) {
                nullHandoffs++
            } else {
                val owner = identities[window] ?: if (owners.size < identityCapacity) {
                    Owner(owners.size + 1, window, NavigationFrameMetricsRecorder.markNanos()).also {
                        identities[window] = it
                        owners += it
                    }
                } else null
                if (owner == null) overflowHandoffs++
                if (latest !== owner) {
                    latest = owner
                    eventLocked("identity-handoff", null, owner?.id)
                }
            }
        }
    }

    fun checkpoint(label: String) {
        checkControl()
        val next = synchronized(gate) {
            check(!sealed)
            eventLocked("phase", label, latest?.id)
            latest
        }
        if (next !== active) {
            active?.let { finishOwner(it, "observed-replacement") }
            active = next
        }
        if (next != null && next.finished) {
            recordFailure(next, IllegalStateException("Previously finished Window returned; no adoption or retry"))
        }
        if (next != null && !next.attempted) {
            next.attempted = true
            next.request = NavigationFrameMetricsRecorder.markNanos()
            try {
                next.recorder = NavigationFrameMetricsRecorder.open(next.window)
                next.returned = NavigationFrameMetricsRecorder.markNanos()
            } catch (error: Throwable) { recordFailure(next, error) }
        }
    }

    private fun eventLocked(kind: String, phase: String?, id: Int?) {
        attempts++
        if (events.size < eventCapacity) events += JSONObject().put("sequence", attempts)
            .put("nanos", NavigationFrameMetricsRecorder.markNanos()).put("kind", kind)
            .put("phase", phase ?: JSONObject.NULL).put("windowId", id ?: JSONObject.NULL)
        else droppedEvents++
    }

    private fun recordFailure(owner: Owner, error: Throwable) {
        owner.errors += error.stackTraceToString()
        failure = NavigationFrameMetricsRecorder.mergeFailure(failure, error)
    }

    private fun finishOwner(owner: Owner, reason: String) {
        if (owner.finished) return
        owner.finished = true
        val recorder = owner.recorder ?: return
        owner.end = NavigationFrameMetricsRecorder.markNanos()
        synchronized(gate) { eventLocked("finish-request", reason, owner.id) }
        try {
            val start = checkNotNull(owner.returned)
            val end = checkNotNull(owner.end)
            check(end > start) { "No nonempty registered observation interval" }
            owner.result = recorder.finish(start, end)
        } catch (error: Throwable) {
            recordFailure(owner, error)
        } finally {
            // Invalid bounds leave the primitive open; successful/failed terminal finish makes close idempotent.
            owner.emergencyCloseAttempted = true
            try { recorder.close() } catch (error: Throwable) { recordFailure(owner, error) }
            owner.result = recorder.lastResult ?: owner.result
            val result = owner.result
            if (result == null || !result.listenerRemovalConfirmed || !result.handlerTerminalReached || !result.workerStopped) {
                recordFailure(owner, IllegalStateException("Frame recorder teardown not confirmed"))
            }
            owner.finishReturned = NavigationFrameMetricsRecorder.markNanos()
        }
    }

    /** Seal handoffs and finish every owner before either receipt performs file IO. */
    fun finalizeRecording(): Throwable? {
        checkControl()
        val retained = synchronized(gate) {
            check(!sealed)
            eventLocked("method-finish", null, latest?.id)
            sealed = true
            owners.toList()
        }
        finalizedOwners = retained
        // Never hold gate across Main registration/removal or worker joins.
        retained.forEach { owner ->
            try { finishOwner(owner, "method-finally-retirement-unknown") }
            catch (error: Throwable) { recordFailure(owner, error) }
        }
        finalizedEnd = NavigationFrameMetricsRecorder.markNanos()
        return failure
    }

    /** primary is the original test-body error only; export errors propagate separately. */
    fun export(primary: Throwable?) {
        checkControl()
        check(!exportAttempted) { "Frame receipt is exported once" }
        exportAttempted = true
        val retained = checkNotNull(finalizedOwners) { "Frame owners must be finalized before export" }
        val ended = checkNotNull(finalizedEnd)
        val document = JSONObject().put("schema", 1)
            .put("protocol", "existing-popup-transition-window-received-subset-v1")
            .put("testId", testId).put("api", Build.VERSION.SDK_INT)
            .put("implementationBase", "c32c0ef1b55c04ef37fa6f576c88d05bb5a7833f")
            .put("measurementStatus", if (retained.isEmpty()) "unavailable-no-window" else "incomplete-or-unavailable-per-window")
            .put("sourceAssociation", "Pinned test/helper bytes and Git SHA supplied by runner evidence")
            .put("clock", "System.nanoTime / FrameMetrics.VSYNC_TIMESTAMP; never Compose virtual time")
            .put("startedNanos", started).put("endedNanos", ended)
            .put("bodyOutcome", if (primary == null) "returned" else "failed")
            .put("primaryFailure", primary?.stackTraceToString() ?: JSONObject.NULL)
            .put("lifecycleFailure", failure?.stackTraceToString() ?: JSONObject.NULL)
            .put("sealed", true).put("completeWindowMeasurement", false).put("tailStatus", "INCOMPLETE")
            .put("identityCapacity", identityCapacity).put("eventCapacity", eventCapacity)
            .put("eventAttempts", attempts).put("droppedEvents", droppedEvents)
            .put("nullHandoffs", nullHandoffs).put("overflowHandoffs", overflowHandoffs)
            .put("events", JSONArray(events)).put("windows", JSONArray(retained.map { ownerJson(it) }))
            .put("limits", JSONArray(listOf(
                "Opening before registration is unobserved; reduced opening may entirely precede registration.",
                "Registration request/return brackets completion; no exact Main registration stamp is exposed.",
                "Registration at existing checkpoints adds blocking scheduling perturbation; no extra draws/readiness/waiters.",
                "Retirement/detachment is not observed: old owners finish on observed replacement or finally; gaps remain unknown.",
                "Missing, detached, unregistered and empty subsets are unavailable, never zero-frame success.",
                "Original captures, marker/readback work and idle time remain in observed intervals; phases are not presentation boundaries.",
                "No fixed-eight-draw admission, full population, complete delivery tail, input-to-present or performance claim."
            )))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = nativeEvidenceDirectory("popup-frame-work", context.getExternalFilesDir(null))
        val safe = testId.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val target = File.createTempFile("$safe-$started-", ".json", directory)
        val partial = File(directory, "${target.name}.partial")
        // Reserve a unique final name, then publish only the sealed document.
        check(target.delete()) { "Cannot release reserved frame receipt: $target" }
        partial.writeText(document.toString(2) + "\n")
        check(partial.renameTo(target)) { "Cannot publish frame receipt: $target" }
    }

    private fun ownerJson(owner: Owner): JSONObject {
        val result = owner.result
        val snapshot = result?.snapshot
        val requestedIntervalMatches = snapshot != null && snapshot.startInclusiveNanos == owner.returned &&
            snapshot.endExclusiveNanos == owner.end
        return JSONObject().put("windowId", owner.id).put("identityHash", System.identityHashCode(owner.window))
            .put("windowClass", owner.window.javaClass.name).put("discoveredNanos", owner.discovered)
            .put("openAttempted", owner.attempted).put("openRequestNanos", owner.request ?: JSONObject.NULL)
            .put("openReturnedNanos", owner.returned ?: JSONObject.NULL).put("requestedEndNanos", owner.end ?: JSONObject.NULL)
            .put("finishReturnedNanos", owner.finishReturned ?: JSONObject.NULL)
            .put("closeFallbackInvoked", owner.emergencyCloseAttempted)
            .put("requestedIntervalMatches", requestedIntervalMatches)
            .put("measurementStatus", when {
                result == null || snapshot == null || !requestedIntervalMatches -> "unavailable"
                owner.errors.isNotEmpty() -> "lifecycle-failed"
                snapshot.storedCount == 0 -> "unavailable-empty-received-subset"
                else -> "incomplete-received-subset"
            }).put("errors", JSONArray(owner.errors))
            .put("completeWindowMeasurement", false).put("tailStatus", "INCOMPLETE")
            .put("result", result?.let {
                JSONObject().put("listenerRemovalConfirmed", it.listenerRemovalConfirmed)
                    .put("handlerTerminalReached", it.handlerTerminalReached).put("workerStopped", it.workerStopped)
                    .put("callbacksRejectedAtReturn", it.callbacksRejectedAtReturn)
                    .put("invalidCallbacksAtReturn", it.invalidCallbacksAtReturn)
                    .put("listenerDropsNotRecordedAtReturn", it.listenerDropsNotRecordedAtReturn)
                    .put("snapshot", snapshot?.let { s -> snapshotJson(s) } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
    }

    private fun snapshotJson(s: NavigationFrameWindow.Snapshot): JSONObject = JSONObject()
        .put("startInclusiveNanos", s.startInclusiveNanos).put("endExclusiveNanos", s.endExclusiveNanos)
        .put("deadlineMetricsSupported", s.deadlineMetricsSupported).put("tailStatus", s.tailStatus.name)
        .put("receivedCount", s.receivedCount).put("storedCount", s.storedCount)
        .put("reportedListenerDropCount", s.reportedListenerDropCount).put("bufferOverflowCount", s.bufferOverflowCount)
        .put("beforeWindowCount", s.beforeWindowCount).put("inWindowCount", s.inWindowCount)
        .put("afterWindowCount", s.afterWindowCount).put("lateInWindowCount", s.lateInWindowCount)
        .put("incompleteTail", s.incompleteTail).put("hasQualityFailure", s.hasQualityFailure)
        .put("observations", JSONArray((0 until s.storedCount).map { index ->
            val o = s[index]
            JSONObject().put("deliveryIndex", index).put("frameTimestampNanos", o.frameTimestampNanos)
                .put("callbackArrivalNanos", o.callbackArrivalNanos).put("totalDurationNanos", o.totalDurationNanos)
                .put("deadlineDurationNanos", o.deadlineDurationNanos ?: JSONObject.NULL)
                .put("listenerDroppedCount", o.listenerDroppedCount).put("position", o.position.name)
                .put("arrivedAtOrAfterEnd", o.arrivedAtOrAfterEnd).put("lateInWindow", o.lateInWindow)
                .put("exceedsReportedDeadline", o.exceedsReportedDeadline ?: JSONObject.NULL)
        }))

    private fun checkControl() {
        check(Thread.currentThread() === control && Looper.myLooper() != Looper.getMainLooper()) {
            "Popup frame lifecycle must remain on the creating non-main test thread"
        }
    }
}
