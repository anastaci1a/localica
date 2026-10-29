package io.github.anastaci1a.localica

import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

private val fixtureCostSequence = AtomicLong()
private val fixtureCostStack = ThreadLocal.withInitial { ArrayDeque<Long>() }

/** Fixture diagnostics only. CPU excludes other processes, the emulator host and GPU work.
 * Nested phases overlap their parents; never add both as independent elapsed work.
 * Reads the Compose clock but never advances it or changes a test deadline.
 */
internal fun <T> ComposeContentTestRule.fixtureCostPhase(name: String, body: () -> T): T {
    if (InstrumentationRegistry.getArguments().getString("fixtureCostTrace") != "true") return body()
    val owner = Thread.currentThread()
    val stack = fixtureCostStack.get()
    val parent = stack.peekLast() ?: 0L
    val sequence = fixtureCostSequence.incrementAndGet()
    val wall = System.nanoTime()
    val processCpu = Process.getElapsedCpuTime()
    val threadCpu = Debug.threadCpuTimeNanos()
    val clock = mainClock.currentTime
    stack.addLast(sequence)
    var primary: Throwable? = null
    try {
        return body()
    } catch (failure: Throwable) {
        primary = failure
        try { recordFixtureFailureThreads(name, failure) }
        catch (diagnosticFailure: Throwable) {
            if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
        }
        throw failure
    } finally {
        try {
            check(Thread.currentThread() === owner && stack.removeLast() == sequence)
            val wallEnd = System.nanoTime()
            val processEnd = Process.getElapsedCpuTime()
            val threadEnd = Debug.threadCpuTimeNanos()
            val clockEnd = mainClock.currentTime
            check(wallEnd >= wall && clockEnd >= clock)
            val row = JSONObject().put("sequence", sequence).put("parent", parent)
                .put("phase", name).put("api", Build.VERSION.SDK_INT)
                .put("pid", Process.myPid()).put("thread", owner.name)
                .put("start_nanos", wall).put("end_nanos", wallEnd).put("wall_nanos", wallEnd - wall)
                .put("process_cpu_millis", if (processCpu >= 0 && processEnd >= processCpu) processEnd - processCpu else JSONObject.NULL)
                .put("thread_cpu_nanos", if (threadCpu >= 0 && threadEnd >= threadCpu) threadEnd - threadCpu else JSONObject.NULL)
                .put("compose_clock_start_millis", clock).put("compose_clock_end_millis", clockEnd)
                .put("compose_clock_delta_millis", clockEnd - clock)
                .put("body_error", primary?.javaClass?.name ?: JSONObject.NULL)
            Log.i("LocalicaFixtureCost", row.toString())
        } catch (traceFailure: Throwable) {
            val original = primary
            if (original == null) throw traceFailure
            if (original !== traceFailure) original.addSuppressed(traceFailure)
        }
    }
}
