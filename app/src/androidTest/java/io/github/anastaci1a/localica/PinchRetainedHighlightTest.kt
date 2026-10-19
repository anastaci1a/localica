package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PinchRetainedHighlightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun virtualizedCurrentRowRetainsItsHighlightThroughRecognitionAndReversal() = retained(false)
    @Test fun rightToLeftVirtualizedCurrentRowRetainsItsHighlightThroughRecognitionAndReversal() = retained(true)

    @Test fun snapshotPreservesTranslucentInkAndPaddedRows() {
        var captured: PixelMap? = null
        compose.setContent {
            val layer = rememberGraphicsLayer()
            LaunchedEffect(layer) {
                val density = Density(1f)
                // An odd width exercises ImageReader row-stride padding on API 26.
                layer.record(density, LayoutDirection.Ltr, IntSize(13, 7)) {
                    repeat(7) { row ->
                        drawRect(Color(1f, row / 8f, .25f, .13f),
                            topLeft = Offset(0f, row.toFloat()), size = androidx.compose.ui.geometry.Size(13f, 1f))
                    }
                }
                captured = layer.pinchSnapshot(density, LayoutDirection.Ltr).toPixelMap()
            }
        }
        compose.waitUntil(5000) { captured != null }
        val pixels = requireNotNull(captured)
        assertEquals(13, pixels.width)
        assertEquals(7, pixels.height)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            assertEquals("Snapshot alpha at $x,$y", .13f, color.alpha, .01f)
            assertEquals("Snapshot red must not be premultiplied twice at $x,$y", 1f, color.red, .04f)
            assertEquals("Snapshot blue/channel order at $x,$y", .25f, color.blue, .04f)
            assertEquals("Snapshot row order at $x,$y", y / 8f, color.green, .04f)
        }
    }

    private fun retained(rtl: Boolean) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "retained-highlight"))
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5, listSize = ListSize.Large)
        // Mix title heights so retention is checked with real, independently
        // measured source cards rather than an artificial uniform row fixture.
        val tracks = (0..39).map { index -> Track("retained:$index",
            if (index % 2 == 0) "Song $index with a long two-line title" else "$index",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        val library = LibraryUiState(LibrarySnapshot(
            folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        val playlists = PlaylistState()
        val playing = "retained:10"
        val queue = ListeningQueue(id = "retained-highlight", name = "Retention regression",
            entries = tracks.map { QueueEntry(id = it.id, track = it) }, currentEntryId = playing)
        val queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        var pointersDown = false
        prefs.write(route, initial, overridden)
        val persistedInitial = prefs.read(route)
        try {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Magenta)) {
                        Box(Modifier.size(360.dp, 480.dp).testTag("retained-scene")) {
                            BrowserScreen(library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                                queueState = queueState, playingEntryId = playing,
                                queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                            PresentationMarker(Modifier.align(AbsoluteAlignment.TopLeft)) { stage }
                        }
                    }
                }
            } }
            val list = compose.onNodeWithTag("browser-list")
            val current = compose.onNodeWithTag("media-$playing")
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-$playing").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val scene = compose.onNodeWithTag("retained-scene")
            val sceneBounds = scene.fetchSemanticsNode().boundsInRoot
            val sourceBounds = current.fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val firstArt = compose.onNodeWithTag("browse-art-retained:0", true).fetchSemanticsNode().boundsInRoot
            val x = viewport.width / 2f
            val y = firstArt.center.y - viewport.top
            fun highlightPixels(label: String): Int {
                compose.runOnIdle { stage++ }
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                val left = (sourceBounds.left - sceneBounds.left).toInt().coerceAtLeast(0)
                val right = (sourceBounds.right - sceneBounds.left).toInt().coerceAtMost(pixels.width)
                val top = (sourceBounds.top - sceneBounds.top).toInt().coerceAtLeast(0)
                val bottom = (sourceBounds.bottom - sceneBounds.top).toInt().coerceAtMost(pixels.height)
                val highlighted = (top until bottom).flatMap { py -> (left until right).mapNotNull { px ->
                    val c = pixels[px, py]
                    if (c.red - c.green > .07f && c.blue - c.green > .07f) px to py else null
                } }
                val count = highlighted.size
                if (label == "source" || label == "reversed" || label == "returned") {
                    assertTrue("The current-song contour stays visible at $label", highlighted.isNotEmpty())
                    assertEquals("Full selection width at $label", (right - left).toFloat(),
                        (highlighted.maxOf { it.first } - highlighted.minOf { it.first } + 1).toFloat(), 3f)
                    assertEquals("Full selection height at $label", (bottom - top).toFloat(),
                        (highlighted.maxOf { it.second } - highlighted.minOf { it.second } + 1).toFloat(), 3f)
                }
                android.util.Log.i("PinchRetainedHighlight", "rtl=$rtl stage=$label ink=$count bounds=$sourceBounds scene=$sceneBounds")
                val exported = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val root = exported?.let { java.io.File(it) } ?: requireNotNull(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
                val directory = java.io.File(root, "pinch-retained-highlight").also { check(it.isDirectory || it.mkdirs()) }
                val bitmap = android.graphics.Bitmap.createBitmap(pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setPixels(IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() },
                        0, pixels.width, 0, 0, pixels.width, pixels.height)
                    java.io.File(directory, "rtl-$rtl-$label.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
                return count
            }
            val originalInk = highlightPixels("source")
            assertTrue("The source current row must have a visible highlight", originalInk > 100)
            compose.mainClock.autoAdvance = false
            pointersDown = true
            list.performTouchInput {
                down(0, Offset(x - 60f, y)); down(1, Offset(x + 60f, y))
                // Cross recognition with almost zero geometry travel. The playing
                // row is away from the anchor and disappears from the live grid.
                moveTo(0, Offset(x - 57.65f, y)); moveTo(1, Offset(x + 57.65f, y))
            }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "List, Large"
            }
            compose.mainClock.advanceTimeBy(300)
            current.assertDoesNotExist()
            val recognizedInk = highlightPixels("recognized")
            assertTrue("Virtualization must retain the current-song slab: source=$originalInk recognized=$recognizedInk",
                recognizedInk >= originalInk * .7f)
            // Hold a partial transition, then reverse without lifting either finger.
            list.performTouchInput {
                moveTo(0, Offset(x - 48f, y)); moveTo(1, Offset(x + 48f, y))
            }
            compose.mainClock.advanceTimeBy(300)
            list.performTouchInput {
                moveTo(0, Offset(x - 60f, y)); moveTo(1, Offset(x + 60f, y))
            }
            compose.mainClock.advanceTimeBy(400)
            current.assertDoesNotExist()
            val reversedInk = highlightPixels("reversed")
            assertTrue("Reversal must restore the retained highlight: source=$originalInk reversed=$reversedInk",
                reversedInk >= originalInk * .9f)
            list.performTouchInput { cancel() }
            pointersDown = false
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            current.assertIsDisplayed()
            assertEquals(persistedInitial, prefs.read(route))
            val returnedInk = highlightPixels("returned")
            assertTrue("Cancellation must return the ordinary highlight: source=$originalInk returned=$returnedInk",
                returnedInk >= originalInk * .9f)
        } finally {
            try { if (pointersDown) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
            finally {
                compose.mainClock.autoAdvance = true
                prefs.write(route, original, overridden)
            }
        }
    }
}
