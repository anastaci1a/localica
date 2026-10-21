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
import org.junit.Rule
import org.junit.Test

class ListControlsTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0..99).map { n -> Track("song:$n", "Z song " + n.toString().padStart(3, '0'), year = 2001,
        fileName = "$n.mp3", relativePath = "$n.mp3") }
    @Test fun scrollbarPreviewsNameOrYearOnlyWhileDragging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
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
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("media-$current").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Z song 070"))
        compose.onNodeWithContentDescription("Current song").assertIsDisplayed()
        compose.onNodeWithText("Z song 070").performTouchInput { longClick() }
        compose.onNodeWithText("Select song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close selection").performClick()
        compose.onNodeWithContentDescription("Current song").assertIsDisplayed().performClick()
        compose.onNodeWithText("Z song 004").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.runOnIdle { current = queue.entries.last().id }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${queue.entries.last().id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Z song 099").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
    }
}
