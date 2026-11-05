package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** #536: a second, already released pinch cannot be discarded by an older settle. */
class PinchRetargetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun galleryReversalRetargetsThePresentedFrame() = repeatPinch(false, true)
    @Test fun galleryContinuationAdvancesBeyondTheOldTarget() = repeatPinch(false, false)
    @Test fun listGalleryReversalRetargetsThePresentedFrame() = repeatPinch(true, true)
    @Test fun listGalleryContinuationAdvancesBeyondTheOldTarget() = repeatPinch(true, false)
    @Test fun threeRapidPinchesKeepEveryAdjacentStep() = repeatPinch(false, false, 2)

    private fun repeatPinch(fromList: Boolean, reverse: Boolean, furtherPinches: Int = 1) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val saved = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = if (fromList) BrowseStyle(listSize = ListSize.Large)
            else BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 4)
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val cover = File.createTempFile("pinch-retarget-", ".png", directory)
        var held = false
        prefs.write(route, initial, false)
        try {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }
            try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            val uri = Uri.fromFile(cover).toString()
            val tracks = (0..30).map { Track(if (it == 0) uri else "retarget:$it", "Song ${it.toString().padStart(2, '0')}",
                fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().graphicsLayer { }.testTag("retarget-window")) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            fun bounds() = compose.onNodeWithTag("browse-art-$uri", true).fetchSemanticsNode().boundsInRoot
            // Half-coverage against either light or dark backing, including antialiased edges.
            fun red(c: Color) = c.red - maxOf(c.green, c.blue) > .5f
            fun frame(): Rect {
                compose.waitForIdle()
                val laid = bounds()
                val image = compose.onNodeWithTag("retarget-window").captureToImage()
                val pixels = image.toPixelMap()
                val x = laid.center.x.toInt(); val y = laid.center.y.toInt()
                assertTrue("Cover remains painted at $laid", red(pixels[x, y]))
                var left = x; while (left > 0 && red(pixels[left - 1, y])) left--
                var right = x; while (right < pixels.width - 1 && red(pixels[right + 1, y])) right++
                var top = y; while (top > 0 && red(pixels[x, top - 1])) top--
                var bottom = y; while (bottom < pixels.height - 1 && red(pixels[x, bottom + 1])) bottom++
                if (abs(laid.width - (right + 1f - left)) > 1.5f || abs(laid.height - (bottom + 1f - top)) > 1.5f) {
                    File(context.cacheDir, "retarget-$fromList-$reverse.png").outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    android.util.Log.e("PinchRetargetTest", "Laid $laid painted $left,$top..$right,$bottom")
                }
                assertEquals("Painted width matches laid width", laid.width, right + 1f - left, 1.5f)
                assertEquals("Painted height matches laid height", laid.height, bottom + 1f - top, 1.5f)
                assertEquals("Painted left matches laid left", laid.left, left.toFloat(), 1f)
                assertEquals("Painted top matches laid top", laid.top, top.toFloat(), 1f)
                return laid
            }
            compose.waitUntil(5000) { runCatching { frame() }.isSuccess }
            val firstOutward = fromList
            val secondOutward = if (reverse) !firstOutward else firstOutward
            val widthDp = list.fetchSemanticsNode().boundsInRoot.width /
                context.resources.displayMetrics.density
            val firstTarget = initial.zoomStep(firstOutward, widthDp)
            val destinations = generateSequence(firstTarget) { it.zoomStep(secondOutward, widthDp) }
                .take(furtherPinches + 1).toList()
            val finalTarget = destinations.last()
            val endpoints = (listOf(initial) + destinations).map { style ->
                compose.runOnIdle { prefs.write(route, style, false) }
                compose.waitForIdle()
                frame()
            }
            compose.runOnIdle { prefs.write(route, initial, false) }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val frames = mutableListOf(frame())
            val times = mutableListOf(compose.mainClock.currentTime)
            fun advance() { compose.mainClock.advanceTimeByFrame(); frames += frame(); times += compose.mainClock.currentTime }
            fun down() {
                list.performTouchInput { down(0, Offset(centerX - 100f, centerY)); down(1, Offset(centerX + 100f, centerY)) }
                held = true
            }
            fun move(outward: Boolean, progress: Float) {
                val half = 100f * (if (outward) 1f + .8f * progress else 1f - .45f * progress)
                list.performTouchInput {
                    updatePointerTo(0, Offset(centerX - half, centerY))
                    updatePointerTo(1, Offset(centerX + half, centerY))
                    move(delayMillis = 0)
                }
            }
            fun up() { list.performTouchInput { up(0); up(1) }; held = false }
            down()
            repeat(10) { index -> move(firstOutward, (index + 1) * .05f); advance() }
            up()
            repeat(2) { advance() }
            // Both contacts and the entire second pinch occur during the 220 ms settle.
            // Releasing after six frames also exercises deferred adjacent-step ownership.
            repeat(furtherPinches) {
                down()
                repeat(6) { index -> move(secondOutward, (index + 1) * .1f); advance() }
                up()
            }
            repeat(50) { advance() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val result = prefs.read(route)
            assertEquals("The second pinch must commit its own adjacent view", finalTarget.presentation, result.presentation)
            if (finalTarget.presentation == Presentation.List) assertEquals(finalTarget.listSize, result.listSize)
            else assertEquals(finalTarget.itemsPerRow, result.itemsPerRow)
            val log = frames.mapIndexed { index, rect -> "$index @ ${times[index]}: $rect" }.joinToString("\n")
            android.util.Log.i("PinchRetargetTest", "list=$fromList reverse=$reverse\n$log")
            times.zipWithNext().forEach { (a, b) -> assertEquals("Sample every frame, without hidden input-time advances", 16L, b - a) }
            for ((label, coordinate) in listOf<Pair<String, (Rect) -> Float>>(
                "left" to { it.left }, "top" to { it.top }, "width" to { it.width }, "height" to { it.height })) {
                val travel = endpoints.maxOf(coordinate) - endpoints.minOf(coordinate)
                assertEquals("Recorded frames include the settled $label", coordinate(endpoints.last()), coordinate(frames.last()), 1f)
                // 0.1 gesture progress per frame plus the bounded 100 ms catch-up share.
                // This also admits the ordinary 220 ms settle, but rejects an endpoint snap.
                val limit = travel * .22f + 2f
                frames.zipWithNext().forEachIndexed { index, (before, after) ->
                    assertTrue("$label jumps at frame ${index + 1} (limit $limit)\n$log",
                        abs(coordinate(after) - coordinate(before)) <= limit)
                }
            }
        } finally {
            try { if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true; prefs.write(route, saved, overridden); cover.delete() }
        }
    }
}
