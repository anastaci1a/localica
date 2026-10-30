package io.github.anastaci1a.localica

import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/** Ordinary bounded observations only: successful SideEffects are not frames or CPU work. */
internal class PopupCompositionWorkRecorder(private val testId: String) {
    private data class Event(val sequence: Long, val nanos: Long, val kind: String,
        val scope: String?, val windowId: Int, val progress: Float?, val phase: String?)
    private data class WindowIdentity(val id: Int, val identityHash: Int, val type: String)
    private val frameWork = PopupFrameWorkRecorder(testId)
    private val gate = Any()
    private val capacity = 8192
    private val windowCapacity = 64
    private val scopes = listOf("dialog", "geometry", "content", "unknown")
    private val windows = IdentityHashMap<Window, Int>()
    private val windowIdentities = mutableListOf<WindowIdentity>()
    // Slot 0 is a null/unknown native window; final slot is explicit identity overflow.
    private val counts = Array(windowCapacity + 2) { LongArray(scopes.size) }
    private val events = ArrayList<Event>(capacity)
    private val startedNanos = System.nanoTime()
    private var sequence = 0L
    private var totalCommits = 0L
    private var phaseRecords = 0L
    private var droppedRecords = 0L
    private var nullWindowCommits = 0L
    private var unassignedWindowCommits = 0L
    private var recordingSealed = false

    val observer: (String, Window?, Float?) -> Unit = { scope, window, progress ->
        synchronized(gate) {
            if (!recordingSealed) {
                val scopeIndex = scopes.indexOf(scope).takeIf { it >= 0 } ?: scopes.lastIndex
                val identity = when {
                    window == null -> 0
                    windows.containsKey(window) -> windows.getValue(window)
                    windows.size < windowCapacity -> (windows.size + 1).also { id ->
                        windows[window] = id
                        windowIdentities += WindowIdentity(id, System.identityHashCode(window), window.javaClass.name)
                    }
                    else -> windowCapacity + 1
                }
                if (identity == 0) nullWindowCommits++
                if (identity == windowCapacity + 1) unassignedWindowCommits++
                counts[identity][scopeIndex]++
                totalCommits++
                append("commit", scopes[scopeIndex], identity, progress, null)
            }
        }
        frameWork.publish(window)
    }

    init { phase("method-start") }

    fun phase(label: String) {
        synchronized(gate) {
            if (!recordingSealed) {
                phaseRecords++
                append("phase", null, 0, null, label)
            }
        }
        frameWork.checkpoint(label)
    }

    /** Called under gate, no Snapshot reads/writes, control callbacks or log/file IO. */
    private fun append(kind: String, scope: String?, windowId: Int, progress: Float?, phase: String?) {
        sequence++
        val stamp = System.nanoTime()
        if (events.size < capacity) events += Event(sequence, stamp, kind, scope, windowId, progress, phase)
        else droppedRecords++
    }

    fun finish(primary: Throwable?) {
        var combined = primary
        try { finishComposition(primary) } catch (error: Throwable) {
            combined = NavigationFrameMetricsRecorder.mergeFailure(combined, error)
        } finally {
            frameWork.finish(combined)
        }
        if (primary == null) combined?.let { throw it }
    }

    private fun finishComposition(primary: Throwable?) {
        try {
            val document = synchronized(gate) {
                check(!recordingSealed) { "Composition recorder is exported once" }
                recordingSealed = true
                val endedNanos = System.nanoTime()
                fun progress(value: Float?): Any = when {
                    value == null -> JSONObject.NULL
                    value.isFinite() -> value
                    else -> "NONFINITE:$value"
                }
                val aggregate = JSONArray()
                counts.forEachIndexed { window, values ->
                    values.forEachIndexed { scope, count ->
                        if (count != 0L) aggregate.put(JSONObject().put("windowId", window)
                            .put("scope", scopes[scope]).put("committedExecutions", count))
                    }
                }
                JSONObject().put("schema", 1).put("testId", testId)
                    .put("bodyOutcome", if (primary == null) "returned" else "failed")
                    .put("primaryFailure", primary?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL)
                    .put("startedNanos", startedNanos).put("endedNanos", endedNanos)
                    .put("clock", "System.nanoTime; not Compose virtual time or presentation timestamps")
                    .put("rawCapacity", capacity).put("windowIdentityCapacity", windowCapacity)
                    .put("totalRecordAttempts", sequence).put("totalCommittedExecutions", totalCommits)
                    .put("phaseRecordCount", phaseRecords).put("retainedRecords", events.size)
                    .put("droppedRecords", droppedRecords).put("nullWindowCommits", nullWindowCommits)
                    .put("unassignedWindowCommits", unassignedWindowCommits)
                    .put("rawRecordsComplete", droppedRecords == 0L)
                    .put("perWindowIdentityComplete", unassignedWindowCommits == 0L && nullWindowCommits == 0L)
                    .put("nullWindowId", 0).put("identityOverflowId", windowCapacity + 1)
                    .put("aggregateCounts", aggregate)
                    .put("windows", JSONArray(windowIdentities.map {
                        JSONObject().put("windowId", it.id).put("identityHash", it.identityHash).put("class", it.type)
                    }))
                    .put("records", JSONArray(events.map {
                        JSONObject().put("sequence", it.sequence).put("nanos", it.nanos)
                            .put("kind", it.kind).put("scope", it.scope ?: JSONObject.NULL)
                            .put("windowId", it.windowId).put("geometryProgress", progress(it.progress))
                            .put("phase", it.phase ?: JSONObject.NULL)
                    }))
                    .put("limits", JSONArray(listOf(
                        "Committed SideEffect executions only, not attempts/frames/draws/CPU/allocations.",
                        "Child skipping and observer synchronization/recording perturb the measured composition work.",
                        "Phase labels bound existing helper calls; not atomic presentation or frame boundaries.",
                        "Only existing geometry p is supplied; Dialog/content callbacks never read progress state.",
                        "Window IDs use object identity within this recorder; null and identity overflow stay explicit.",
                        "Measurement seals before file export; later callbacks are outside this recording window.",
                        "No Search/IME/nested-window/general performance or complete-tail claim."
                    )))
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = nativeEvidenceDirectory("popup-composition-work", context.getExternalFilesDir(null))
            val safe = testId.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val target = File(directory, "$safe-$startedNanos.json")
            val partial = File(directory, "${target.name}.partial")
            partial.writeText(document.toString(2) + "\n")
            check(partial.renameTo(target)) { "Could not publish composition evidence: $target" }
        } catch (exportFailure: Throwable) {
            if (primary == null) throw exportFailure
            if (exportFailure !== primary) primary.addSuppressed(exportFailure)
        }
    }
}
