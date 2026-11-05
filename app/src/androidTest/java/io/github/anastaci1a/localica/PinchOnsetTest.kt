package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #520: the view-size pinch follows the fingers from recognition, without a dead zone or catch-up jump. */
class PinchOnsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinchProgressFollowsTheFingersFromTheFirstMoves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 4), false)
        try {
            val tracks = (0..30).map { Track("onset:$it", "Song ${it.toString().padStart(2, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val art = "browse-art-onset:0"
            fun width() = compose.onNodeWithTag(art, true).fetchSemanticsNode().boundsInRoot.width
            compose.waitForIdle()
            val source = width()
            val list = compose.onNodeWithTag("browser-list")
            compose.mainClock.autoAdvance = false
            // Four to five per row: fingers close at a steady rate from the second finger's landing.
            val start = 200f
            list.performTouchInput { down(0, Offset(centerX - start, centerY)); down(1, Offset(centerX + start, centerY)) }
            compose.mainClock.advanceTimeByFrame()
            val spans = mutableListOf<Float>()
            val widths = mutableListOf<Float>()
            val times = mutableListOf<Long>()
            for (frame in 1..36) {
                val half = start - frame * 2f
                list.performTouchInput {
                    updatePointerTo(0, Offset(centerX - half, centerY))
                    updatePointerTo(1, Offset(centerX + half, centerY))
                    move(delayMillis = 0)
                }
                compose.mainClock.advanceTimeByFrame()
                // Let the render-thread readback finish within the frame, as on a device.
                compose.waitForIdle()
                spans += half / start
                widths += width()
                times += compose.mainClock.currentTime
            }
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val target = width()
            assertEquals(5, prefs.read(route).itemsPerRow)
            val shown = widths.map { (it - source) / (target - source) }
            val implied = spans.map { pinchProgress(it, false) }
            val log = shown.indices.joinToString("\n") { "${it + 1} +${times[it] - times[0]} ms finger %.3f shown %.3f".format(implied[it], shown[it]) }
            times.zipWithNext().forEach { (a, b) ->
                assertEquals("Sample every display frame without implicit pointer-time advances\n$log", 16L, b - a)
            }
            android.util.Log.i("PinchOnsetTest", "4 to 5 per row, progress per frame:\n$log")
            // The 4% direction threshold stays; travel before it is not discarded.
            val recognized = spans.indexOfFirst { it <= 1f / 1.04f }
            val moving = shown.indexOfFirst { it > .001f }
            assertTrue("The view must start to follow within two frames of recognition (frame ${recognized + 1})\n$log",
                moving in 0..recognized + 2)
            for (i in moving until shown.size) {
                val step = shown[i] - shown.getOrElse(i - 1) { 0f }
                val gap = implied.getOrElse(i - 1) { 0f } - shown.getOrElse(i - 1) { 0f }
                // The catch-up ease closes the threshold's gap over several frames, never in one.
                assertTrue("Frame ${i + 1} jumps by $step\n$log", step <= implied[i] - implied.getOrElse(i - 1) { 0f } + .8f * gap + .005f)
                assertTrue("Frame ${i + 1} moves backward by $step\n$log", step >= -.001f)
            }
            // After the 100 ms ease the view is on the fingers, not a tween behind them.
            val caughtUp = times.indexOfFirst { it >= times[moving] + 120 }
            for (i in caughtUp until shown.size)
                assertEquals("Frame ${i + 1} follows the fingers directly\n$log", implied[i], shown[i], .01f)
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, old, false) }
    }
}
