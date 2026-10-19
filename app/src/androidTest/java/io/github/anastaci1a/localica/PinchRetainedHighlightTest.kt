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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
            fun highlightPixels(): Int {
                compose.runOnIdle { stage++ }
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                val left = (sourceBounds.left - sceneBounds.left).toInt().coerceAtLeast(0)
                val right = (sourceBounds.right - sceneBounds.left).toInt().coerceAtMost(pixels.width)
                val top = (sourceBounds.top - sceneBounds.top).toInt().coerceAtLeast(0)
                val bottom = (sourceBounds.bottom - sceneBounds.top).toInt().coerceAtMost(pixels.height)
                return (top until bottom).sumOf { py -> (left until right).count { px ->
                    val c = pixels[px, py]
                    c.red - c.green > .07f && c.blue - c.green > .07f
                } }
            }
            val originalInk = highlightPixels()
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
            assertTrue("Virtualization must retain the current-song slab with the source card",
                highlightPixels() >= originalInk * .7f)
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
            assertTrue("Reversal must restore the retained highlight before the source layout returns",
                highlightPixels() >= originalInk * .9f)
            list.performTouchInput { cancel() }
            pointersDown = false
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            current.assertIsDisplayed()
            assertEquals(persistedInitial, prefs.read(route))
            assertTrue("Cancellation must hand the highlight back to the ordinary row",
                highlightPixels() >= originalInk * .9f)
        } finally {
            try { if (pointersDown) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
            finally {
                compose.mainClock.autoAdvance = true
                prefs.write(route, original, overridden)
            }
        }
    }
}
