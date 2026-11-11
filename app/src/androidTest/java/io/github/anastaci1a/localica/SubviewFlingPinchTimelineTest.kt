package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test

/** #535 (b), replayed from the operator's pointer-location recording (2026-11-04): in a
 * List Compact playlist, a fast fling up, then ~274 ms later (fling still running) both
 * fingers land in one frame and spread vertically (pinch out to List Normal). Real
 * dispatch and the real Choreographer: the Compose test clock stops the fling first. */
class SubviewFlingPinchTimelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val tracks = (0..102).map { Track("timeline:$it", "Song ${it.toString().padStart(3, '0')}",
        artist = "Timeline Artist $it", album = "Timeline Album ${it / 10}", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val playlist = ManualPlaylist(id = "timeline-playlist", name = "Timeline playlist",
        entries = tracks.mapIndexed { i, track -> SongRow("timeline-entry-$i", track) })
    private val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), playlist.name)
    private val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("timeline-root", "Music")), tracks = tracks)

    /** Operator samples in device pixels (1440 px wide, 3.5 px/dp), times in ms from fling down. */
    private class Sample(val at: Double, val first: Pair<Float, Float>, val second: Pair<Float, Float>? = null)
    private val operatorDensity = 3.5f
    private val fling = listOf(Sample(0.0, 1060f to 1627.8f), Sample(7.5, 1082.8f to 1525.6f),
        Sample(16.4, 1119.6f to 1392.4f), Sample(24.6, 1178f to 1220.3f), Sample(33.0, 1260.4f to 987.2f),
        Sample(41.0, 1424.9f to 521f))
    private val flingUp = 49.5
    private val pinchDown = 323.8
    // Pointer 0 per recorded frame (8.3 ms apart); pointer 1 follows the same normalized path.
    private val first = listOf(859.5f to 1695.5f, 859.5f to 1695.5f, 859.5f to 1695.5f, 859.5f to 1695.5f,
        859.5f to 1695.5f, 859.5f to 1695.5f, 851.7f to 1707.8f, 845.5f to 1720.6f, 841.5f to 1729.9f,
        836.7f to 1745.9f, 829.2f to 1772.5f, 819.7f to 1807.5f, 805.0f to 1853.3f, 792.0f to 1895.9f,
        781.2f to 1940.9f, 773.0f to 1973.5f, 767.5f to 2005.5f, 765.0f to 2026.9f, 763.5f to 2041.4f,
        762.5f to 2050.5f, 761.7f to 2056.6f, 761.7f to 2061.1f, 761.5f to 2063.5f)
    private val secondStart = 952f to 1019f
    private val secondEnd = 1067f to 803f
    private val pinchHoldUntil = 533.0 + 323.8
    private val pinchUp = 549.7 + 323.8
    // Emulator readbacks and handoffs can take 1.7 s; the stuck transaction expires only after 3 s.
    private val retireDeadlineMillis = 2400L

    private fun event(down: Long, at: Long, action: Int, points: List<Pair<Float, Float>>): MotionEvent {
        val props = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = .03f } }
        return MotionEvent.obtain(down, at, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    private fun inject(event: MotionEvent, sync: Boolean = false) { automation.injectInputEvent(event, sync); event.recycle() }

    private fun replay(scale: Float, originX: Float, originY: Float, secondGapMillis: Double, extraDelayMillis: Double) {
        fun map(p: Pair<Float, Float>) = originX + p.first * scale to originY + p.second * scale
        val start = SystemClock.uptimeMillis()
        fun waitUntil(at: Double): Long {
            val target = start + at.toLong()
            while (SystemClock.uptimeMillis() < target) SystemClock.sleep(1)
            return SystemClock.uptimeMillis()
        }
        val flingDown = waitUntil(0.0)
        inject(event(flingDown, flingDown, MotionEvent.ACTION_DOWN, listOf(map(fling[0].first))))
        fling.drop(1).forEach { inject(event(flingDown, waitUntil(it.at), MotionEvent.ACTION_MOVE, listOf(map(it.first)))) }
        inject(event(flingDown, waitUntil(flingUp), MotionEvent.ACTION_UP, listOf(map(fling.last().first))))

        val total = first.zipWithNext { a, b -> kotlin.math.hypot(b.first - a.first, b.second - a.second) }.sum()
        var travelled = 0f
        val frames = first.mapIndexed { i, point ->
            if (i > 0) travelled += kotlin.math.hypot(point.first - first[i - 1].first, point.second - first[i - 1].second)
            val f = travelled / total
            point to (secondStart.first + (secondEnd.first - secondStart.first) * f to secondStart.second + (secondEnd.second - secondStart.second) * f)
        }
        val downAt = pinchDown + extraDelayMillis
        val down = waitUntil(downAt)
        inject(event(down, down, MotionEvent.ACTION_DOWN, listOf(map(frames[0].first))))
        inject(event(down, waitUntil(downAt + secondGapMillis), MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(map(frames[0].first), map(frames[0].second))))
        frames.forEachIndexed { i, (a, b) ->
            if (i > 0) inject(event(down, waitUntil(downAt + secondGapMillis + i * 8.3), MotionEvent.ACTION_MOVE, listOf(map(a), map(b))))
        }
        val (a, b) = frames.last()
        inject(event(down, waitUntil(pinchHoldUntil + extraDelayMillis), MotionEvent.ACTION_MOVE, listOf(map(a), map(b))))
        inject(event(down, waitUntil(pinchUp + extraDelayMillis), MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(map(a), map(b))))
        inject(event(down, waitUntil(pinchUp + extraDelayMillis + 4), MotionEvent.ACTION_UP, listOf(map(a))), sync = true)
    }

    private fun drag(x: Float, fromY: Float, toY: Float, millis: Long, steps: Int) {
        val down = SystemClock.uptimeMillis()
        inject(event(down, down, MotionEvent.ACTION_DOWN, listOf(x to fromY)))
        for (i in 1..steps) {
            SystemClock.sleep(millis / steps)
            inject(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, listOf(x to fromY + (toY - fromY) * i / steps)))
        }
        inject(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(x to toY)), sync = true)
    }

    @Test fun operatorFlingThenSpreadRetiresItsHeaderTransaction() {
        val args = InstrumentationRegistry.getArguments()
        val gaps = (args.getString("gaps") ?: "0,0,0,2,2,2,4,4").split(",").map { it.toDouble() }
        val prefs = BrowsePreferences(instrumentation.targetContext)
        val original = prefs.read(route); val overridden = prefs.overridden(route)
        val failures = mutableListOf<String>()
        try {
            for (gap in gaps) {
                prefs.write(route, BrowseStyle(listSize = ListSize.Compact), true)
                // The header reports a frame for every drawn frame of a live header transaction.
                var lastHeaderFrame = 0L
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { FixtureTime.WithClock { EngineRecord { LocalicaTheme { Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalCollectionHeaderFrameObserver provides { lastHeaderFrame = SystemClock.uptimeMillis() }) {
                            BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(listOf(playlist), loading = false),
                                route, {}, {}, {}, { _, _, _ -> }, null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
                        }
                    } } } } } }
                    SystemClock.sleep(1500)
                    var origin = IntArray(2); var density = 1f
                    scenario.onActivity { activity ->
                        activity.window.decorView.getLocationOnScreen(origin)
                        density = activity.resources.displayMetrics.density
                    }
                    val label = "gap=$gap"
                    replay(density / operatorDensity, origin[0].toFloat(), origin[1].toFloat(), gap, 0.0)
                    val released = SystemClock.uptimeMillis()
                    SystemClock.sleep(retireDeadlineMillis)
                    // Scroll down, as the operator did: a live transaction redraws its header every frame.
                    val x = origin[0] + 200f * density; val y = origin[1] + 300f * density
                    drag(x, y, y + 120f * density, 300, 12)
                    SystemClock.sleep(100)
                    var lingered = 0L
                    scenario.onActivity { lingered = lastHeaderFrame - released }
                    val committed = prefs.read(route).listSize == ListSize.Normal
                    android.util.Log.i("P535", "timeline $label lastHeaderFrame=+$lingered committed=$committed")
                    // A settled pinch retires within a few hundred ms. One awaiting header geometry that
                    // can never arrive holds every row frozen at the source until its 3 s expiry.
                    if (lingered > retireDeadlineMillis) failures += "$label: header transaction still drawing ${lingered} ms after release"
                }
            }
        } finally { prefs.write(route, original, overridden) }
        assertTrue("Frozen: $failures", failures.isEmpty())
    }
}
