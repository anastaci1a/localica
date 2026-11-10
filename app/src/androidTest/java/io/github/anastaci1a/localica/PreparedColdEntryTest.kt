package io.github.anastaci1a.localica

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.StrictMode
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.anastaci1a.localica.lastfm.LastFmWorker
import io.github.anastaci1a.localica.playback.PlaybackService
import io.github.anastaci1a.localica.scanner.ScanService
import io.github.anastaci1a.localica.editing.TagEditService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The host runs each mode in a separate, force-stopped process at the seeded library scale. */
class PreparedColdEntryTest {
    @Test fun enterWithoutAnActivity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val app = instrumentation.targetContext.applicationContext as LocalicaApplication
        val mode = args.getString("preparedEntry") ?: "playback"
        val pid = android.os.Process.myPid()
        fun assertNoActivity() = instrumentation.runOnMainSync {
            Stage.values().filter { it != Stage.DESTROYED }.forEach { assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).isEmpty()) }
            if (args.getString("expectFatalPolicy") == "true") {
                val expected = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                    .detectCustomSlowCalls().detectUnbufferedIo().penaltyLog().penaltyDeath().build()
                assertEquals(expected.toString(), StrictMode.getThreadPolicy().toString())
            }
        }
        assertNoActivity()
        when (mode) {
            "playback" -> {
                val future = withContext(Dispatchers.Main) {
                    MediaController.Builder(app, SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync()
                }
                val controller = withContext(Dispatchers.IO) { future.get(30, TimeUnit.SECONDS) }
                try {
                    withContext(Dispatchers.Main) { controller.play(); controller.pause() }
                    app.preferences.awaitReady()
                } finally {
                    withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
                    app.stopService(Intent(app, PlaybackService::class.java))
                }
                withTimeout(30_000) {
                    while (withContext(Dispatchers.Main) { app.queues.playbackSample() != null }) delay(20)
                }
            }
            "scan" -> {
                ContextCompat.startForegroundService(app, Intent(app, ScanService::class.java))
                app.preferences.awaitReady()
                withTimeout(30_000) { while (app.scanner.state.value.message != "Scan complete.") delay(20) }
            }
            "tag" -> {
                ContextCompat.startForegroundService(app, Intent(app, TagEditService::class.java))
                // Force the service callback through Main before inspecting shutdown, without creating its controller here.
                delay(1000)
                instrumentation.waitForIdleSync()
                withTimeout(30_000) {
                    val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    while (manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == TagEditService::class.java.name }) delay(20)
                }
            }
            "lastfm" -> {
                // The public constructor selects the actual application runtime. No username prevents network work.
                val worker = TestListenableWorkerBuilder<LastFmWorker>(app).build()
                assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
            }
            else -> error("Unknown prepared entry: $mode")
        }
        app.preferences.awaitReady()
        args.getString("expectSeededTracks")?.toInt()?.let { count ->
            withTimeout(30_000) { app.library.snapshots.first { it.tracks.size == count } }
        }
        assertNoActivity()
        assertEquals(pid, android.os.Process.myPid())
        val runtime = Runtime.getRuntime()
        val stats = Debug.getRuntimeStats()
        val line = "prepared-entry=$mode;pid=$pid;activities=0;managedUsed=" + (runtime.totalMemory() - runtime.freeMemory()) +
            ";managedMax=" + runtime.maxMemory() + ";nativeAllocated=" + Debug.getNativeHeapAllocatedSize() +
            ";gcCount=" + stats["art.gc.gc-count"] + ";gcTimeMs=" + stats["art.gc.gc-time"] +
            ";blockingGcCount=" + stats["art.gc.blocking-gc-count"] + ";blockingGcTimeMs=" + stats["art.gc.blocking-gc-time"]
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }
}
