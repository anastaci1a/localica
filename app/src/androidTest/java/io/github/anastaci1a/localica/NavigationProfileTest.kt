package io.github.anastaci1a.localica

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Real Android/Compose clocks: intentionally no Compose test clock or idling auto-advance.
 * Synthetic missing-art fixtures isolate navigation/rendering; not a physical-device benchmark.
 * Accessibility readiness is NOT pixel visibility or animation completion. */
class NavigationProfileTest {
    @Test fun albumOne() = profile(MainTab.Albums, 1, "Perf album", "Play album")
    @Test fun albumTen() = profile(MainTab.Albums, 10, "Perf album", "Play album")
    @Test fun artistTen() = profile(MainTab.Artists, 10, "Perf artist", "Play selected albums")
    @Test fun playlistTen() = profile(MainTab.Playlists, 10, "Perf playlist", "Play playlist")

    private fun profile(tab: MainTab, count: Int, title: String, action: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(tab), reducedMotion = false))
        val tracks = (0 until count).map { Track("profile:$it", "Profile song $it", artist = "Perf artist",
            albumArtist = "Perf artist", album = "Perf album", trackNumber = it + 1,
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val library = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("profile-root", "Profile")), tracks), loading = false)
        val playlist = ManualPlaylist(id = "profile-list", name = "Perf playlist", entries = tracks.mapIndexed { i, t -> SongRow("profile-row:$i", t) })
        val handlerThread = HandlerThread("profile-frames").apply { start() }
        val lock = Any()
        val frames = mutableListOf<Long>()
        var measuring = false
        var dropped = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, missed -> synchronized(lock) {
            if (measuring) { frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION); dropped += missed }
        } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                activity.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                    playlist = PlaylistState(listOf(playlist), loading = false)) } }
            }
            assertTrue("Root ready", device.wait(Until.hasObject(By.text(title)), 10000))
            SystemClock.sleep(1200)
            repeat(4) { cycle ->
                val item = device.findObject(By.text(title)) ?: error("Missing root item")
                val bounds = item.visibleBounds
                synchronized(lock) { frames.clear(); dropped = 0; measuring = true }
                val start = SystemClock.elapsedRealtimeNanos()
                device.click(bounds.centerX(), bounds.centerY())
                assertTrue("Detail action ready", device.wait(Until.hasObject(By.desc(action)), 10000))
                val ready = SystemClock.elapsedRealtimeNanos()
                // Fixed wall-clock observation includes the unchanged 600ms entry wave.
                SystemClock.sleep(1800)
                val samples: List<Long>
                val missed: Int
                synchronized(lock) { measuring = false; samples = frames.toList(); missed = dropped }
                val sorted = samples.sorted()
                fun percentile(p: Double) = if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
                Log.i("NavigationProfile", JSONObject().put("tab", tab.name).put("items", count).put("cycle", cycle)
                    .put("phase", if (cycle == 0) "first-route" else "warm-route")
                    .put("accessibilityReadyMs", (ready-start)/1e6).put("frames", samples.size)
                    .put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("maxMs", percentile(1.0))
                    .put("over16ms", samples.count { it > 16_666_667 }).put("over32ms", samples.count { it > 33_333_334 })
                    .put("listenerDropped", missed).put("javaUsedBytes", Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    .put("nativeAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize()).toString())
                val back = device.findObject(By.desc("Back")) ?: error("Missing Back")
                back.click()
                assertTrue("Returned to root", device.wait(Until.gone(By.desc(action)), 10000))
                assertTrue(device.wait(Until.hasObject(By.text(title)), 10000))
                SystemClock.sleep(1000)
            }
        } catch (failure: Throwable) {
            val dump = java.io.ByteArrayOutputStream()
            runCatching { device.dumpWindowHierarchy(dump) }
            Log.e("NavigationProfileHierarchy", dump.toString("UTF-8"))
            throw failure
        } finally {
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            scenario.close(); handlerThread.quitSafely()
        }
    }
}
