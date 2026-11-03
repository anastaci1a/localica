package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #505/#510: eased geometry never reverses between frames and lands on whole pixels. */
class MotionSubpixelTest {
    @get:Rule val compose = createComposeRule()

    private fun assertMonotone(label: String, values: List<Float>) {
        val rising = values.last() >= values.first()
        values.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue("$label reverses by ${if (rising) a - b else b - a} px at frame $index: $values",
                if (rising) b >= a - .01f else b <= a + .01f)
        }
    }

    @Test fun scriptedPinchMovesEveryCoverEdgeMonotonically() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(listSize = ListSize.Large), false)
        try {
            val tracks = (0..20).map { Track("sub:$it", "Song ${it.toString().padStart(2, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme { Box(Modifier.width(300.dp)) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val tags = listOf("browse-art-sub:0", "browse-art-sub:1", "browse-text-sub:0")
            fun bounds() = tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot }
            val resting = bounds()
            list.performTouchInput {
                down(0, Offset(centerX - 50, 200f)); down(1, Offset(centerX + 50, 200f))
                moveTo(0, Offset(centerX - 60, 200f)); moveTo(1, Offset(centerX + 60, 200f))
            }
            // Recognition hands over after the real render-thread readback.
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                kotlin.math.abs(bounds()[0].left - resting[0].left) > .5f ||
                    kotlin.math.abs(bounds()[0].width - resting[0].width) > .5f
            }
            compose.mainClock.advanceTimeByFrame()
            val frames = mutableListOf<List<Rect>>()
            for (step in 0..48) {
                val spread = 60f + step * 1.25f
                list.performTouchInput { moveTo(0, Offset(centerX - spread, 200f)); moveTo(1, Offset(centerX + spread, 200f)) }
                compose.mainClock.advanceTimeByFrame()
                frames += bounds()
            }
            for ((index, tag) in tags.withIndex()) {
                val samples = frames.map { it[index] }
                assertTrue("$tag must move during the sweep", samples.first() != samples.last())
                assertMonotone("$tag left", samples.map { it.left })
                assertMonotone("$tag right", samples.map { it.right })
                assertMonotone("$tag top", samples.map { it.top })
                assertMonotone("$tag bottom", samples.map { it.bottom })
                assertMonotone("$tag width", samples.map { it.width })
            }
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(5, prefs.read(route).itemsPerRow)
            // At rest the cover is laid out on whole pixels with no residual transform.
            val settled = compose.onNodeWithTag("browse-art-sub:0", true).fetchSemanticsNode().boundsInRoot
            for (edge in listOf(settled.left, settled.top, settled.right, settled.bottom))
                assertEquals("Settled cover edge $settled", Math.round(edge).toFloat(), edge, .001f)
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, old, false) }
    }

    @Test fun playerExpansionSweepGrowsMonotonicallyAndCoversTheWindowEdges() {
        val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000)
        val visual = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }.asImageBitmap(), PlayerPalette(Color(0xFF303040), Color(0xFF303040), Color(0xFF303040)))
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(Color.Green).testTag("window")) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = ArtworkLayout.Floating), progress,
                {}, {}, {}, {}, {}, {}, visual = visual) { Text("Album-only action") }
        } } }
        fun snap(p: Float) {
            compose.runOnIdle {
                val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue(update.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        fun surface() = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val sweep = (0..19).map { it / 50f } + (400..1000 step 4).map { it / 1000f }
        val surfaces = mutableListOf<Rect>()
        for (p in sweep) { snap(p); surfaces += surface() }
        assertMonotone("player left", surfaces.map { it.left })
        assertMonotone("player right", surfaces.map { it.right })
        assertMonotone("player top", surfaces.map { it.top })
        assertMonotone("player bottom", surfaces.map { it.bottom })
        assertMonotone("player width", surfaces.map { it.width })
        assertMonotone("player height", surfaces.map { it.height })
        assertEquals("Full player covers the window exactly", window, surfaces.last())
        for (p in listOf(.998f, .999f, 1f)) {
            snap(p)
            val full = surface()
            assertTrue("Within a pixel of full size both edges land together at $p: $full / $window",
                full.left == window.left && full.right == window.right || full.left > window.left && full.right < window.right)
            if (p < 1f) continue
            val image = compose.onNodeWithTag("window").captureToImage().toPixelMap()
            for (y in (image.height / 4) until (image.height * 3 / 4) step 8) for ((edge, inner) in
                listOf(0 to 4, image.width - 1 to image.width - 5)) {
                val outer = image[edge, y]
                val reference = image[inner, y]
                assertTrue("Edge pixel ($edge,$y) blends the backdrop: $outer vs $reference",
                    outer.green <= reference.green + .03f)
            }
        }
    }
}
