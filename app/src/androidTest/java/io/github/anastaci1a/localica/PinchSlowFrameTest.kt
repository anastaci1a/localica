package io.github.anastaci1a.localica

import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test

/** #560 on the device clock, not the test clock: a phone's first destination frame (or a
 * release's recomposition) can take 100+ ms. The catch-up ease and the release settle must
 * still spread over several frames instead of finishing in the frame after the slow one. */
class PinchSlowFrameTest {
    private class Shown(val time: Long, val progress: Float, val fingers: Float, val held: Boolean)

    @Test fun recognitionCatchUpSurvivesASlowDestinationFrame() {
        val shown = pinch(stallHeld = true)
        val held = shown.filter { it.held }
        val start = held.first()
        // The fingers may still be moving: measure every step against the whole recognition gap.
        val gap = held.maxOf { it.fingers } - start.progress
        assertTrue("The fingers are ahead of the first destination frame: $gap", gap > .05f)
        val steps = held.zipWithNext { a, b -> (b.progress - a.progress) / gap }
        val log = held.joinToString("\n") { "+${it.time - start.time} ms shown %.3f fingers %.3f".format(it.progress, it.fingers) }
        assertTrue("One frame closes ${steps.max()} of the recognition gap after a slow frame\n$log", steps.max() < .5f)
        held.forEach { assertTrue("The view never runs ahead of the fingers\n$log", it.progress <= it.fingers + .01f) }
        val caught = held.indexOfFirst { it.progress >= it.fingers - .005f }
        assertTrue("The catch-up spans several frames, not one\n$log", caught >= 4)
    }

    @Test fun releaseSettleSurvivesASlowFirstFrame() {
        val scale = Settings.Global.getFloat(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val shown = pinch(stallHeld = false)
        val released = shown.dropWhile { it.held }
        if (scale == 0f) {
            // Animations off: the settle keeps the system scale and lands on its first frame.
            assertTrue("With animations off the release lands at once: ${released.map { it.progress }}",
                released.isEmpty() || released.drop(1).all { it.progress == 0f })
            return
        }
        val from = shown.last { it.held }.progress
        assertTrue("The release starts from a partial pinch: $from", from > .1f)
        val steps = (listOf(from) + released.map { it.progress }).zipWithNext { a, b -> (a - b) / from }
        val log = released.joinToString("\n") { "+${it.time - released.first().time} ms shown %.3f".format(it.progress) }
        assertTrue("One frame returns ${steps.max()} of the cancelled pinch after a slow frame\n$log", steps.max() < .5f)
    }

    /** Spreads two fingers to a quarter of the transition within a few frames, holds, then cancels.
     * The overlay's draw witness stalls two frames: the first destination frames, or the first released ones. */
    private fun pinch(stallHeld: Boolean): List<Shown> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(listSize = ListSize.Normal), false)
        val shown = mutableListOf<Shown>()
        var stalls = 0
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val tracks = (0..80).map { Track("slow:$it", "Song ${it.toString().padStart(2, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
                scenario.onActivity { activity -> activity.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                        PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                        queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                } } } }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1500)
                var location = IntArray(2)
                var width = 0; var height = 0
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    location = IntArray(2).also { view.getLocationOnScreen(it) }
                    width = view.width; height = view.height
                }
                val x = location[0] + width / 2f
                val y = location[1] + height / 2f
                pinchDrawProbe = { frame ->
                    if (frame.destinationReady) {
                        shown += Shown(SystemClock.uptimeMillis(), frame.progress, frame.fingers, frame.held)
                        // A slow frame, as the phone's destination composition or release recomposition.
                        if (frame.held == stallHeld && stalls < 2) { stalls++; SystemClock.sleep(120) }
                    }
                }
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, half: Float, pointers: Int = 2) {
                    val properties = Array(pointers) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coordinates = Array(pointers) { i -> MotionEvent.PointerCoords().apply {
                        this.x = x; this.y = y + (if (i == 0) -half else half); pressure = 1f; size = 1f } }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                val begin = 150f
                send(MotionEvent.ACTION_DOWN, begin, 1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), begin)
                // Progress (scale - 1) / .8: a 1.24 span is .3 of the transition, reached before the source readback ends.
                for (step in 1..3) send(MotionEvent.ACTION_MOVE, begin * (1f + .24f * step / 3f))
                SystemClock.sleep(900)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), begin * 1.24f)
                send(MotionEvent.ACTION_UP, begin * 1.24f, 1)
                SystemClock.sleep(900)
                instrumentation.waitForIdleSync()
                pinchDrawProbe = null
            }
        } finally {
            pinchDrawProbe = null
            prefs.write(route, old, false)
        }
        assertTrue("A slow frame was drawn: $stalls", stalls >= 1)
        return shown
    }
}
