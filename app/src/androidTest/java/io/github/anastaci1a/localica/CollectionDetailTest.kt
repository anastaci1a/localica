package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CollectionDetailTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0..5).map { Track("collection:$it", "Song $it", artist = "Artist", albumArtist = "Artist",
        album = if (it < 3) "First album" else "Second album", trackNumber = it % 3 + 1,
        fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks)

    @Test fun artistOptionsShareOneSurfaceAndReturnToThePersistentOptionsPill() {
        val artist = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(),
                BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, artist.id), "Artist"), {}, {}, {},
                { _, _, _ -> }, null, null, true, false, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        } }
        val trigger = compose.onNodeWithTag("browse-options-trigger").fetchSemanticsNode().boundsInRoot
        for (label in listOf("Sort", "View options")) {
            compose.onNodeWithContentDescription(label).performClick()
            val menu = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            compose.onNodeWithText(if (label == "Sort") "Sort by" else "View mode").assertIsDisplayed()
            if (label == "View options") {
                compose.onNodeWithText("Gallery").assertIsDisplayed().performClick()
                compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Cancel").performClick()
            var returning = menu
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().singleOrNull()?.let { returning = it.boundsInRoot }
            }
            assertTrue("Returns to the persistent Sort/View pill", kotlin.math.abs(returning.center.x - trigger.center.x) < trigger.width)
            compose.mainClock.advanceTimeBy(1500)
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
            compose.onNodeWithTag("browse-options-trigger").assertIsDisplayed()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun artistAlbumsFilterThePlayedSnapshotAndLastDeselectionRestoresAll() {
        val artist = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        var played = emptyList<Track>()
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(),
                BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, artist.id), "Artist"), {}, {}, {},
                { rows, _, _ -> played = rows.map { it.track } }, null, null, true, false,
                queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        } }
        compose.onNodeWithContentDescription("Play selected albums").performClick()
        assertEquals(6, played.size)
        compose.onNodeWithText("First album").performClick()
        compose.onNodeWithContentDescription("Play selected albums").performClick()
        assertEquals(setOf("First album"), played.map { it.album }.toSet())
        compose.onNodeWithText("Second album").performClick()
        compose.onNodeWithContentDescription("Play selected albums").performClick()
        assertEquals(6, played.size)
        compose.onNodeWithText("First album").performClick()
        compose.onNodeWithText("Second album").performClick()
        compose.onNodeWithText("All albums").assertIsSelected()
        compose.onNodeWithContentDescription("Play selected albums").performClick()
        assertEquals(6, played.size)
    }

    @Test fun albumOmitsScopedSearchAndPlaysItsTrackOrder() {
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).first()
        var played = emptyList<Track>()
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(),
                BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), album.title), {}, {}, {},
                { rows, _, _ -> played = rows.map { it.track } }, null, null, true, false,
                queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        } }
        compose.onNodeWithText("Search ${album.title}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play album").performClick()
        assertEquals(listOf(1, 2, 3), played.map { it.trackNumber })
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        compose.onNodeWithContentDescription("View options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collection actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Album actions").performClick()
        compose.onNodeWithText("Select all songs").assertIsDisplayed()
        compose.onNodeWithText("View options").assertDoesNotExist()
        compose.onNodeWithText("Set playlist image").assertDoesNotExist()
    }
}
