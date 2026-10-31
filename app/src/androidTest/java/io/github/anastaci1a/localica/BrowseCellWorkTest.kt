package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/** Resting rows keep only the per-cell work that their visible state needs. */
class BrowseCellWorkTest {
    @get:Rule val compose = createComposeRule()

    private val events = Collections.synchronizedList(mutableListOf<Pair<String, Any>>())
    private fun keys(kind: String) = synchronized(events) { events.filter { it.first == kind }.map { it.second }.toSet() }

    private fun showSongs(style: BrowseStyle, count: Int = 120, body: () -> Unit) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val tracks = (0 until count).map { index -> Track("cell-work:$index", "Song ${index.toString().padStart(3, '0')}",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        val library = LibraryUiState(LibrarySnapshot(
            folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        prefs.write(route, style, false)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalBrowseCellWorkObserver provides { kind, key -> events += kind to key },
                    LocalContextAnchorPlacementObserver provides { bounds -> events += "anchor" to bounds }) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        BrowserScreen(library, PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null,
                            true, false, reducedMotion = true, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                    }
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-cell-work:0").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            body()
        } finally {
            prefs.write(route, original, overridden)
        }
    }

    @Test fun closedCollectionMenusAreComposedOnlyForTheOpenedRow() = showSongs(BrowseStyle(presentation = Presentation.List)) {
        assertTrue("Rows with closed menus must not compose CollectionActions: ${keys("actions")}", keys("actions").isEmpty())
        compose.onNodeWithContentDescription("Actions for Song 002").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Play next").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Only the opened row owns a collection menu", setOf<Any>("cell-work:2"), keys("actions"))
    }

    @Test fun scrollStartAndStopAwayFromTheTopDoNotRecomposeTheScreen() =
        showSongs(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), count = 400) {
            val list = compose.onNodeWithTag("browser-list")
            // Leaving the top legitimately changes the offered return action once.
            list.performScrollToKey("cell-work:150")
            compose.waitForIdle()
            events.clear()
            // Key scrolling toggles isScrollInProgress without a pointer contact.
            list.performScrollToKey("cell-work:300")
            compose.waitForIdle()
            list.performScrollToKey("cell-work:200")
            compose.waitForIdle()
            assertEquals("Scroll progress is observed by effects and derived state, not the screen body", 0,
                synchronized(events) { events.count { it.first == "screen" } })
        }

    @Test fun restingGalleryScrollPublishesNoPerCellPositionsUntilAMenuOpens() =
        showSongs(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5)) {
            val list = compose.onNodeWithTag("browser-list")
            // Reduced motion completes the loading reveal before the first frame.
            events.clear()
            list.performTouchInput { swipeUp(startY = bottom * .8f, endY = bottom * .3f, durationMillis = 300) }
            compose.waitForIdle()
            list.performTouchInput { swipeDown(startY = bottom * .3f, endY = bottom * .7f, durationMillis = 300) }
            compose.waitForIdle()
            assertTrue("Resting rows must not run global-position work while scrolling: ${keys("global-position")}",
                keys("global-position").isEmpty())
            assertTrue("Closed artwork anchors must not publish window geometry while scrolling", keys("anchor").isEmpty())
            assertTrue("Scrolling must not compose any collection menu", keys("actions").isEmpty())

            val key = "cell-work:6"
            list.performScrollToKey(key)
            compose.waitForIdle()
            events.clear()
            val art = compose.onNodeWithTag("browse-art-$key", true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("media-$key").performTouchInput { longClick(center) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Play next").fetchSemanticsNodes().isNotEmpty() }
            val published = synchronized(events) { events.filter { it.first == "anchor" }.map { it.second as androidx.compose.ui.geometry.Rect } }
            assertTrue("Opening publishes the held artwork as the menu origin before the menu composes", published.isNotEmpty())
            val origin = published.first()
            assertEquals("Menu origin keeps the artwork's measured width", art.width, origin.width, 1f)
            assertEquals("Menu origin keeps the artwork's measured height", art.height, origin.height, 1f)
            assertEquals(setOf<Any>(key), keys("actions"))
        }
}
