package io.github.anastaci1a.localica

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private val fixtureThreadFailureRecorded = AtomicBoolean()

/** Failure-only observations, never a thread fence or a replacement test verdict. */
internal fun recordFixtureFailureThreads(phase: String, failure: Throwable) {
    if (InstrumentationRegistry.getArguments().getString("fixtureThreadDiagnostics") != "true" ||
        fixtureThreadFailureRecorded.getAndSet(true)) return
    val causes = generateSequence(failure) { it.cause }.take(16).toList()
    val previous = causes.flatMap { cause ->
        Regex("previousThreadId=(\\d+)").findAll(cause.message.orEmpty()).map { it.groupValues[1].toLong() }.toList()
    }.distinct().sorted()
    val threads = Thread.getAllStackTraces().entries.sortedBy { it.key.id }
    Log.e("LocalicaFixtureThreads", JSONObject().put("kind", "failure-summary")
        .put("phase", phase).put("pid", Process.myPid()).put("observer_java_id", Thread.currentThread().id)
        .put("previous_java_ids", JSONArray(previous))
        .put("matched_previous_java_ids", JSONArray(previous.filter { id -> threads.any { it.key.id == id } }))
        .put("thread_count", threads.size).put("max_stack_frames", 32)
        .put("limits", "Post-failure live Java threads only; missing/dead threads and earlier stacks are unavailable.")
        .toString())
    for ((thread, frames) in threads) {
        Log.e("LocalicaFixtureThreads", JSONObject().put("kind", "thread")
            .put("pid", Process.myPid()).put("java_id", thread.id).put("name", thread.name)
            .put("state", thread.state.name).put("previous_owner_id_match", thread.id in previous)
            .put("stack", JSONArray(frames.take(32).map { it.toString() })).toString())
    }
}
