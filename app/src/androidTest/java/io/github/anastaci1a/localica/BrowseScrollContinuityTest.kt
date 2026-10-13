package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrowseScrollContinuityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slowHeldScrollMovesRowsAndTheirContentsTogetherAcrossVirtualization() = follow(BrowseStyle(), 54)

    @Test fun galleryScrollMovesArtworkAndLabelsWithoutAnIndependentCatchUp() = follow(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3), 54)

    private fun follow(style: BrowseStyle, target: Int) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val old = prefs.read(route)
        val tracks = (0..119).map {
            Track("slow:$it", "Song ${it.toString().padStart(3, '0')}" + if (it % 3 == 0) " with a longer title that wraps onto another line" else "",
                artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3")
        }
        prefs.write(route, style, false)
        try {
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(51)
            compose.mainClock.autoAdvance = false
            list.performTouchInput { down(center); moveBy(Offset(0f, -40f), delayMillis = 32) }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val row = compose.onNodeWithTag("media-slow:$target", useUnmergedTree = true)
            val text = compose.onNodeWithTag("browse-text-slow:$target", useUnmergedTree = true)
            // A row's artwork may be visible while its label is below the viewport.
            // Measuring that clipped label produces a zero rectangle, not its motion.
            text.assertIsDisplayed()
            var previousRow = row.fetchSemanticsNode().boundsInRoot.top
            var previousText = text.fetchSemanticsNode().boundsInRoot.top
            repeat(80) {
                list.performTouchInput { moveBy(Offset(0f, -3f), delayMillis = 16) }
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val nextRow = row.fetchSemanticsNode().boundsInRoot.top
                val nextText = text.fetchSemanticsNode().boundsInRoot.top
                assertEquals("Row follows slow drag at frame $it", -3f, nextRow - previousRow, 1f)
                assertEquals("Text cannot catch up separately at frame $it", nextRow - previousRow, nextText - previousText, 1f)
                previousRow = nextRow; previousText = nextText
            }
            list.performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, old, false) }
    }
}
