package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ListControlsTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0..99).map { n -> Track("song:$n", "Z song " + n.toString().padStart(3, '0'), year = 2001,
        fileName = "$n.mp3", relativePath = "$n.mp3") }
    @Before @After fun clearPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }
    private fun awaitDisplayed(node: SemanticsNodeInteraction) {
        try {
            compose.waitUntil(5000) {
                // Selection first measures at zero occupancy. Its layout write starts
                // the reveal on the next composition, not on the pointer-up itself.
                compose.mainClock.advanceTimeByFrame()
                runCatching { node.assertIsDisplayed() }.isSuccess
            }
            node.assertIsDisplayed()
        } catch (failure: Throwable) {
            runCatching {
                val roots = compose.onAllNodes(isRoot())
                roots.fetchSemanticsNodes().indices.forEach { roots[it].printToLog("ListControls", maxDepth = 12) }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    @Test fun scrollbarPreviewsNameOrYearOnlyWhileDragging() {
        var route by mutableStateOf(BrowseRoute(MainTab.Songs))
        compose.setContent { LocalicaTheme { key(route.key) { BrowserScreen(
            LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(loading = false), route,
            {}, {}, {}, { _, _, _ -> }, null, null, true, false) } } }
        val bar = compose.onNodeWithTag("browse-scrollbar")
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 500) }
        compose.onNodeWithTag("scroll-preview").assertIsDisplayed()
        compose.onNodeWithText("Z").assertIsDisplayed()
        bar.performTouchInput { up() }
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
        compose.runOnIdle { route = BrowseRoute(MainTab.Songs, querySort = TrackSort.Year) }
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 500) }
        compose.onNodeWithTag("scroll-preview").assertIsDisplayed()
        compose.onNode(hasText("2001") and hasAnyAncestor(hasTestTag("scroll-preview"))).assertIsDisplayed()
        bar.performTouchInput { up() }
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
    }
    @Test fun queueCurrentSongButtonReturnsToTheOccurrenceAndHandlesTheEnd() {
        val queue = QueueEdits.create(tracks, 4, "Queue")
        var current by mutableStateOf(queue.entries[4].id)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val playlists = PlaylistState(loading = false)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
        val queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        compose.setContent { LocalicaTheme { BrowserScreen(
            library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
            queueState = queues, playingEntryId = current) } }
        awaitDisplayed(compose.onNodeWithTag("media-$current"))
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Z song 070"))
        compose.onNodeWithContentDescription("Current song").assertIsDisplayed()
        val selected = compose.onNodeWithTag("media-${queue.entries[70].id}")
        val selectionTarget = compose.onNodeWithText("Z song 070")
        var held = false
        try {
            selectionTarget.performTouchInput { down(center); held = true; advanceEventTime(650); moveTo(center) }
            selected.assertIsSelected()
            compose.onNodeWithText("Select song").assertDoesNotExist()
            compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
            compose.onNodeWithText("1 selected").assertDoesNotExist()
            selectionTarget.performTouchInput { up(); held = false }
        } finally { if (held) selectionTarget.performTouchInput { cancel() } }
        awaitDisplayed(compose.onNodeWithText("1 selected"))
        compose.onNodeWithContentDescription("Close selection").performClick()
        awaitDisplayed(compose.onNodeWithContentDescription("Current song"))
        compose.onNodeWithContentDescription("Current song").performClick()
        awaitDisplayed(compose.onNodeWithTag("media-${queue.entries[4].id}"))
        compose.onNodeWithText("Z song 004").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.runOnIdle { current = queue.entries.last().id }
        awaitDisplayed(compose.onNodeWithTag("media-${queue.entries.last().id}"))
        compose.onNodeWithText("Z song 099").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
    }
}
