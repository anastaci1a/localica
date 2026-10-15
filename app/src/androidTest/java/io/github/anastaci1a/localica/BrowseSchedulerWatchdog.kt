package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Temporary read-only diagnostic: never joins Main or changes the test scheduler. */
internal object BrowseSchedulerWatchdog {
    @Volatile private var active = false
    @Volatile private var phase = "not started"
    fun phase(value: String) {
        if (!active) return
        phase = value
        Log.i("BrowseScheduler", "${SystemClock.uptimeMillis()} phase=$value")
    }
    fun rule() = object : TestWatcher() {
        private var executor: ScheduledExecutorService? = null
        override fun starting(description: Description) {
            active = true
            phase(description.methodName + ": starting")
            executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "browse-test-watchdog").apply { isDaemon = true }
            }.also { service ->
                service.scheduleWithFixedDelay({
                    runCatching {
                        Log.e("BrowseScheduler", "${SystemClock.uptimeMillis()} watchdog phase=$phase")
                        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
                            Log.e("BrowseScheduler", "thread=${thread.name} id=${thread.id} state=${thread.state}")
                            stack.take(80).forEach { Log.e("BrowseScheduler", "  at $it") }
                        }
                    }.onFailure { Log.e("BrowseScheduler", "Stack diagnostic failed", it) }
                }, 30, 60, TimeUnit.SECONDS)
            }
        }
        override fun finished(description: Description) {
            phase(description.methodName + ": finished")
            active = false
            executor?.shutdownNow()
            executor = null
        }
    }
}
