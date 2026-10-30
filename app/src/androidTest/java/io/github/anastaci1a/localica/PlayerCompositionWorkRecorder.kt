package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ordinary committed execution observations, not frames, attempted composition or CPU work. */
internal class PlayerCompositionWorkRecorder(private val testId: String) {
    private data class Event(val sequence: Long, val nanos: Long, val kind: String,
        val scope: String?, val progress: Float?, val phase: String?)
    private val gate = Any()
    private val capacity = 8192
    private val scopes = listOf("root", "surface-content", "unknown")
    private val counts = LongArray(scopes.size)
    private val events = ArrayList<Event>(capacity)
    private val startedNanos = System.nanoTime()
    private var attempts = 0L
    private var commits = 0L
    private var phases = 0L
    private var dropped = 0L
    private var unknown = 0L
    private var sealed = false

    val observer: (String, Float?) -> Unit = { scope, progress ->
        synchronized(gate) {
            if (!sealed) {
                val index = scopes.indexOf(scope).takeIf { it >= 0 } ?: scopes.lastIndex
                counts[index]++
                if (index == scopes.lastIndex) unknown++
                commits++
                append("commit", scopes[index], progress, null)
            }
        }
    }

    fun phase(label: String) = synchronized(gate) {
        if (!sealed) {
            phases++
            append("phase", null, null, label)
        }
    }

    /** Under gate only: no Snapshot reads/writes, controls, logging or file IO. */
    private fun append(kind: String, scope: String?, progress: Float?, phase: String?) {
        attempts++
        val nanos = System.nanoTime()
        if (events.size < capacity) events += Event(attempts, nanos, kind, scope, progress, phase)
        else dropped++
    }

    fun finish(primary: Throwable?) {
        try {
            val document = synchronized(gate) {
                check(!sealed) { "Player composition recording exports once" }
                sealed = true
                val endedNanos = System.nanoTime()
                JSONObject().put("schema", 1).put("testId", testId)
                    .put("implementationBase", "58bdb7b9ecf6311e8cb6a3a7cb68a108fd9726b6")
                    .put("sourceAssociation", "Exact helper/test/app Git source binding supplied by runner evidence")
                    .put("bodyOutcome", if (primary == null) "returned" else "failed")
                    .put("primaryFailure", primary?.stackTraceToString() ?: JSONObject.NULL)
                    .put("startedNanos", startedNanos).put("endedNanos", endedNanos)
                    .put("clock", "System.nanoTime; separate from existing elapsedRealtimeNanos trace and Compose virtual clock")
                    .put("sealed", true).put("rawCapacity", capacity).put("totalRecordAttempts", attempts)
                    .put("totalCommittedExecutions", commits).put("phaseRecordCount", phases)
                    .put("retainedRecords", events.size).put("droppedRecords", dropped)
                    .put("unknownScopeCommits", unknown).put("rawRecordsComplete", dropped == 0L)
                    .put("aggregateCounts", JSONArray(scopes.mapIndexed { index, name ->
                        JSONObject().put("scope", name).put("committedExecutions", counts[index])
                    }))
                    .put("records", JSONArray(events.map {
                        JSONObject().put("sequence", it.sequence).put("nanos", it.nanos).put("kind", it.kind)
                            .put("scope", it.scope ?: JSONObject.NULL).put("phase", it.phase ?: JSONObject.NULL)
                            .put("progress", when {
                                it.progress == null -> JSONObject.NULL
                                it.progress.isFinite() -> it.progress
                                else -> "NONFINITE:${it.progress}"
                            })
                    }))
                    .put("limits", JSONArray(listOf(
                        "Successful inline SideEffect executions only, not attempts, descendants, frames, jobs, CPU, allocation or performance.",
                        "Root passes existing p; Surface content passes null without a new progress or polarity.frame read.",
                        "Ordinary synchronized callback/recording perturb work; aggregate counts stay available if raw prefix overflows.",
                        "Phase labels accompany unchanged existing trace calls; their elapsedRealtimeNanos values are a different clock.",
                        "Recording seals before file IO; later callbacks are outside this recording window.",
                        "Original fixture autoAdvance=true restoration and swallowed pointer-cancel cleanup failure are unchanged.",
                        "Two existing held-lyrics fixtures only; descriptive baseline without matching optimization/control or count threshold."
                    )))
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = nativeEvidenceDirectory("player-composition-work", context.getExternalFilesDir(null))
            val safe = testId.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val target = File.createTempFile("$safe-$startedNanos-", ".json", directory)
            val partial = File(directory, "${target.name}.partial")
            check(target.delete()) { "Could not release reserved player receipt: $target" }
            partial.writeText(document.toString(2) + "\n")
            check(partial.renameTo(target)) { "Could not publish player composition receipt: $target" }
        } catch (failure: Throwable) {
            if (primary == null) throw failure
            if (primary !== failure) primary.addSuppressed(failure)
        }
    }
}
