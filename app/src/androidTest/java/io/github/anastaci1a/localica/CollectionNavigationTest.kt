package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("missing", "Repeated", fileName = "tone.mp3", relativePath = "tone.mp3")

    @Test fun playlistTapUsesOccurrenceAndSelectionPreventsPlayback() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(track, track))
        var played: String? = null
        compose.setContent {
            LocalicaTheme {
                PlaylistScreen(PlaylistUiState(PlaylistState(listOf(playlist), false), playlist, rows = playlist.entries),
                    emptyList(), null, null, {}, {}, { played = it }, null, true)
            }
        }
        val first = "song-" + playlist.entries.first().id
        val last = "song-" + playlist.entries.last().id
        compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag(last))
        compose.onNodeWithTag(last).performClick()
        compose.runOnIdle { assertEquals(playlist.entries.last().id, played); played = null }
        compose.onNode(hasContentDescription("Select Repeated") and hasAnyAncestor(hasTestTag(last))).performClick()
        compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.runOnIdle { assertNull(played) }
        compose.onNodeWithTag("playlist-list").performScrollToIndex(0)
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Add to playlist").assertIsDisplayed()
        compose.onNodeWithText("Remove from playlist").assertIsDisplayed()
    }

    @Test fun albumGroupsAndViewChoicesAreReachable() {
        val tagged = track.copy(album = "Compilation", albumArtist = "Various Artists")
        val snapshot = LibrarySnapshot(tracks = listOf(tagged))
        val groups = LibraryViews.groups(snapshot, SongListView.Albums, LibraryViews.defaults(SongListView.Albums))
        var opened: String? = null
        var view: SongListView? = null
        compose.setContent {
            LocalicaTheme {
                LibraryScreen(LibraryUiState(snapshot, loading = false, view = SongListView.Albums, groups = groups,
                    visibleTracks = emptyList()), null, true, {}, {}, onView = { view = it }, onGroup = { opened = it })
            }
        }
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Compilation"))
        compose.onNodeWithText("Compilation").performClick()
        compose.runOnIdle { assertEquals(groups.single().id, opened) }
        compose.onNodeWithTag("library-list").performScrollToIndex(0)
        compose.onNodeWithText("View: Albums").performClick()
        compose.onNodeWithText("Album artists").performClick()
        compose.runOnIdle { assertEquals(SongListView.AlbumArtists, view) }
    }
}
