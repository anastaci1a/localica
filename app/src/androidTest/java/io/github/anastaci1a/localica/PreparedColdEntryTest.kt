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
            "playback" -> verifyPlayback(app, instrumentation)
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

    private suspend fun verifyPlayback(app: LocalicaApplication, instrumentation: android.app.Instrumentation) {
        // Ordinary suites may have used a service/player earlier in this PID. Begin a fresh service episode.
        app.stopService(Intent(app, PlaybackService::class.java))
        val services = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        withTimeout(30_000) {
            while (services.getRunningServices(Int.MAX_VALUE).any { it.service.className == PlaybackService::class.java.name }) delay(20)
        }
        instrumentation.waitForIdleSync()
        val episode = "playback-episode=" + java.util.UUID.randomUUID()
        android.util.Log.i("PreparedEntryProof", episode)
        fun episodeLog() = logcat(instrumentation).substringAfter(episode, "")
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { future.get(30, TimeUnit.SECONDS) }
        try {
            // The cold connection starts the service; transport commands need a selected, ready native item.
            app.preferences.awaitReady()
            val track = withTimeout(30_000) { app.library.snapshots.first { it.tracks.isNotEmpty() }.tracks.first() }
            app.queues.settings { it.copy(songTap = io.github.anastaci1a.localica.queue.SongTap.NewQueue, shuffle = false) }
            app.queues.tap(listOf(track), 0)
            suspend fun awaitMain(label: String, condition: () -> Boolean) {
                android.util.Log.i("PreparedEntryProof", "stage=$label pid=" + android.os.Process.myPid())
                try {
                    withTimeout(30_000) { while (!withContext(Dispatchers.Main) { condition() }) delay(20) }
                } catch (timeout: TimeoutCancellationException) {
                    val observed = withContext(NonCancellable + Dispatchers.Main) {
                        "items=" + controller.mediaItemCount + ";state=" + controller.playbackState +
                            ";playWhenReady=" + controller.playWhenReady + ";sample=" + app.queues.playbackSample() +
                            ";queueLoading=" + app.queues.state.value.loading + ";problem=" + app.queues.state.value.problem
                    }
                    throw AssertionError("$label timed out: $observed", timeout)
                }
            }
            awaitMain("native-attachment") {
                controller.currentMediaItem?.mediaMetadata?.extras?.getString("trackId") == track.id &&
                    app.queues.playbackSample()?.playbackVisit != null
            }
            val queueId = checkNotNull(app.queues.state.value.library.activeId)
            withContext(Dispatchers.Main) { controller.play() }
            awaitMain("play") { controller.playWhenReady }
            withContext(Dispatchers.Main) { controller.pause() }
            awaitMain("pause") { !controller.playWhenReady && !controller.isPlaying }
            awaitMain("seek-command-ready") {
                controller.playbackState == androidx.media3.common.Player.STATE_READY &&
                    controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
            withContext(Dispatchers.Main) { controller.seekTo(1234) }
            awaitMain("native-seek-position") { app.queues.playbackSample()?.positionMs == 1234L }
            val playerIds = Regex("ExoPlayerImpl\\s*:\\s*Init ([0-9a-f]+)")
                .findAll(episodeLog()).map { it.groupValues[1] }.distinct().toList()
            assertEquals("Require exactly one actual service-owned engine", 1, playerIds.size)
            val playerId = playerIds.single()
            // Drop the binding before stopService; a bound Android service cannot be destroyed yet.
            withContext(Dispatchers.Main) {
                MediaController.releaseFuture(future)
                assertFalse(controller.isConnected)
            }
            app.stopService(Intent(app, PlaybackService::class.java))
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            withTimeout(30_000) {
                while (manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == PlaybackService::class.java.name }) delay(20)
            }
            // Read through an independent Room owner after shutdown; the queue writer must finish its cursor checkpoint.
            val database = androidx.room.Room.databaseBuilder(app,
                io.github.anastaci1a.localica.queue.QueueDatabase::class.java, "queues.db").build()
            try {
                withTimeout(30_000) {
                    while (true) {
                        val saved = withContext(Dispatchers.IO) { database.queues().read().active }
                        if (saved != null && saved.id == queueId && saved.positionMs == 1234L) break
                        delay(20)
                    }
                }
            } finally { database.close() }
            val released = Regex("ExoPlayerImpl\\s*:\\s*Release $playerId\\b")
            withTimeout(30_000) { while (!released.containsMatchIn(episodeLog())) delay(20) }
            // The release log is emitted inside release(); an idle Main receipt follows the completed callback.
            instrumentation.waitForIdleSync()
            withContext(Dispatchers.Main) { assertNull(app.queues.playbackSample()) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "playback-attached=true;play-pause-order=true;controller-released=true;" +
                    "service-stopped=true;durable-position=1234;native-released=true;player-id=$playerId\n")
            })
        } finally {
            withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }

    private fun logcat(instrumentation: android.app.Instrumentation): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("logcat -d -v threadtime --pid=" + android.os.Process.myPid())
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

}
