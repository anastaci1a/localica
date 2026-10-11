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
        compose.onNodeWithContentDescription("Collection actions").performClick()
        compose.onNodeWithText("View options").assertIsDisplayed()
    }
}
