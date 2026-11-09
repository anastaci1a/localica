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
import androidx.compose.ui.graphics.graphicsLayer
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

    /** The viewport paints each live cover from its recorded layer, scaled onto the cover's
     * current bounds. Measure the painted pixels, not only the layout node, on every frame.
     * Primary case (#505): scrolled to the top, the top-left cover from four to five per row. */
    @Test fun galleryPinchPaintsTheTopLeftCoverAtItsLaidOutSizeOnEveryFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        val directory = java.io.File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val cover = java.io.File.createTempFile("pinch-size-", ".png", directory)
        prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 4), false)
        try {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            val uri = android.net.Uri.fromFile(cover).toString()
            val tracks = (0..30).map { Track(if (it == 0) uri else "size:$it", "Song ${it.toString().padStart(2, '0')}",
                fileName = "$it.mp3", relativePath = "$it.mp3") }
            // The app's screens sit under graphics layers, so a root redraw does not re-record the viewport.
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().graphicsLayer { }.testTag("window")) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            fun laid(tag: String) = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
            fun red(color: Color) = color.red > .5f && color.green < .3f && color.blue < .3f
            // Cover: painted extent through the centre of its laid-out bounds (rounded corners stay outside).
            // Title: the ink bounding box inside its laid-out box (below the cover edge), against the background beside it.
            class Frame(val cover: Rect, val coverPainted: Rect, val title: Rect, val titleInk: Rect)
            // #678 instrumentation (log only).
            var laidNs = 0L; var captureNs = 0L; var scanNs = 0L; var frames = 0
            fun frame(): Frame {
                frames++
                var t = System.nanoTime()
                val bounds = laid("browse-art-$uri")
                val title = laid("browse-text-$uri")
                laidNs += System.nanoTime() - t; t = System.nanoTime()
                val image = compose.onNodeWithTag("window").captureNativeFrame().toPixelMap()
                captureNs += System.nanoTime() - t; t = System.nanoTime()
                val x = bounds.center.x.toInt(); val y = bounds.center.y.toInt()
                assertTrue("Cover must be painted at $bounds", red(image[x, y]))
                var left = x; while (left > 0 && red(image[left - 1, y])) left--
                var right = x; while (right < image.width - 1 && red(image[right + 1, y])) right++
                var top = y; while (top > 0 && red(image[x, top - 1])) top--
                var bottom = y; while (bottom < image.height - 1 && red(image[x, bottom + 1])) bottom++
                val background = image[0, title.center.y.toInt()]
                var ink = Rect(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f)
                for (ty in (title.top + 2).toInt().coerceAtLeast(0) until (title.bottom + 2).toInt().coerceAtMost(image.height))
                    for (tx in (title.left - 2).toInt().coerceAtLeast(0) until (title.right + 2).toInt().coerceAtMost(image.width)) {
                        val c = image[tx, ty]
                        if (!red(c) && kotlin.math.abs(c.red - background.red) + kotlin.math.abs(c.green - background.green) +
                            kotlin.math.abs(c.blue - background.blue) > .45f) ink = Rect(minOf(ink.left, tx.toFloat()),
                            minOf(ink.top, ty.toFloat()), maxOf(ink.right, tx + 1f), maxOf(ink.bottom, ty + 1f))
                    }
                scanNs += System.nanoTime() - t
                return Frame(bounds, Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f), title, ink)
            }
            val methodStart = System.nanoTime()
            compose.waitUntil(5000) { runCatching { frame() }.isSuccess }
            val list = compose.onNodeWithTag("browser-list")
            for (outward in listOf(false, true)) {
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val resting = laid("browse-art-$uri")
                val near = if (outward) 50f else 160f
                val first = if (outward) near + 10f else near - 10f
                list.performTouchInput {
                    down(0, Offset(centerX - near, centerY)); down(1, Offset(centerX + near, centerY))
                    moveTo(0, Offset(centerX - first, centerY)); moveTo(1, Offset(centerX + first, centerY))
                }
                // Recognition hands over after the real render-thread readback.
                compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); laid("browse-art-$uri") != resting }
                val frames = mutableListOf<Frame>()
                for (step in 1..40) {
                    val spread = if (outward) first + step * .6f else first - step * 3f
                    list.performTouchInput { moveTo(0, Offset(centerX - spread, centerY)); moveTo(1, Offset(centerX + spread, centerY)) }
                    compose.mainClock.advanceTimeByFrame()
                    frames += frame()
                }
                fun Rect.text() = "%.2f,%.2f w %.2f h %.2f".format(left, top, width, height)
                val log = frames.mapIndexed { index, it -> "$index cover laid ${it.cover.text()} painted ${it.coverPainted.text()}" +
                    " | title laid ${it.title.text()} ink ${it.titleInk.text()}" }.joinToString("\n")
                android.util.Log.i("MotionSubpixelTest", "${if (outward) "5 to 4" else "4 to 5"} per row, top-left item:\n$log")
                assertMonotone("painted width", frames.map { it.coverPainted.width })
                assertMonotone("painted height", frames.map { it.coverPainted.height })
                frames.forEachIndexed { index, it ->
                    // Each antialiased float edge adds at most one partly covered pixel.
                    assertEquals("Frame $index painted width\n$log", it.cover.width, it.coverPainted.width, 1.5f)
                    assertEquals("Frame $index painted height\n$log", it.cover.height, it.coverPainted.height, 1.5f)
                    assertEquals("Frame $index painted left\n$log", it.cover.left, it.coverPainted.left, 1f)
                    assertEquals("Frame $index painted top\n$log", it.cover.top, it.coverPainted.top, 1f)
                }
                list.performTouchInput { up(0); up(1) }
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                assertEquals(if (outward) 4 else 5, prefs.read(route).itemsPerRow)
                val settled = frame()
                assertEquals("Settled cover is painted exactly at its bounds", settled.cover, settled.coverPainted)
            }
            android.util.Log.i("Trim678", "MotionSubpixelTest.galleryPinch totalMs=${(System.nanoTime() - methodStart) / 1_000_000} frames=$frames " +
                "laidMs=${laidNs / 1_000_000} captureMs=${captureNs / 1_000_000} scanMs=${scanNs / 1_000_000}")
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, old, false); cover.delete() }
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
                {}, {}, {}, {}, {}, {}, visual = visual) { ink -> DrawInkText("Album-only action", ink) }
        } } }
        // #678 instrumentation (log only).
        val methodStart = System.nanoTime()
        var snapNs = 0L; var surfaceNs = 0L
        fun snap(p: Float) {
            val t = System.nanoTime()
            compose.runOnIdle {
                val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue(update.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            snapNs += System.nanoTime() - t
        }
        fun surface(): Rect {
            val t = System.nanoTime()
            return compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot.also { surfaceNs += System.nanoTime() - t }
        }
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val sweep = (0..19).map { it / 50f } + (400..1000 step 4).map { it / 1000f }
        val surfaces = mutableListOf<Rect>()
        for (p in sweep) { snap(p); surfaces += surface() }
        android.util.Log.i("Trim678", "MotionSubpixelTest.playerExpansionSweep sweepMs=${(System.nanoTime() - methodStart) / 1_000_000} samples=${sweep.size} " +
            "snapMs=${snapNs / 1_000_000} surfaceMs=${surfaceNs / 1_000_000}")
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
            val captureStart = System.nanoTime()
            val image = compose.onNodeWithTag("window").captureNativeFrame().toPixelMap()
            val scanStart = System.nanoTime()
            for (y in (image.height / 4) until (image.height * 3 / 4) step 8) for ((edge, inner) in
                listOf(0 to 4, image.width - 1 to image.width - 5)) {
                val outer = image[edge, y]
                val reference = image[inner, y]
                assertTrue("Edge pixel ($edge,$y) blends the backdrop: $outer vs $reference",
                    outer.green <= reference.green + .03f)
            }
            android.util.Log.i("Trim678", "MotionSubpixelTest.playerExpansionSweep totalMs=${(System.nanoTime() - methodStart) / 1_000_000} " +
                "edgeCaptureMs=${(scanStart - captureStart) / 1_000_000} edgeScanMs=${(System.nanoTime() - scanStart) / 1_000_000}")
        }
    }
}
