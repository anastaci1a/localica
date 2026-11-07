package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test

class CrossViewSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun albumSelectionFollowsSongsIntoArtistSongsAndDuplicatePlaylistRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interfaceStore = context.getSharedPreferences("interface", 0)
        val browseStore = context.getSharedPreferences("browse-style", 0)
        interfaceStore.edit().clear().commit()
        browseStore.edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(
            tabs = listOf(MainTab.Albums, MainTab.Artists, MainTab.Songs, MainTab.Playlists), reducedMotion = true))
        val tracks = (1..2).map { Track("identity:$it", "Song $it", artist = "Shared artist", album = "Shared album",
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Duplicate mix"), listOf(tracks[0], tracks[0], tracks[1]))
        try {
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                    playlist = PlaylistState(listOf(playlist), loading = false))
            } }
            compose.awaitRowActions("Shared album").performClick()
            compose.onNodeWithText("Select all songs").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.awaitExpectedWindowFocus(compose.onNodeWithContentDescription("Collapse selection"))
            compose.onNodeWithContentDescription("Collapse selection").performClick()

            fun tab(name: String) { compose.onNodeWithContentDescription(name).performClick() }
            // Collection subviews hide the tab bar from semantics, so leave them first.
            fun back() { compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle() }
            fun song(key: String): SemanticsNodeInteraction {
                compose.onNodeWithTag("browser-list").performScrollToKey(key)
                return compose.onNodeWithTag("media-$key").assertIsDisplayed()
            }
            tab("Artists")
            compose.onNodeWithText("Shared artist").performClick()
            song(tracks[0].id).assertIsSelected()
            song(tracks[1].id).assertIsSelected()
            back()
            tab("Songs")
            song(tracks[0].id).assertIsSelected().performClick().assertIsNotSelected()
            compose.onNodeWithTag("selection-minimized").assert(hasText("1 selected", substring = true))
            tab("Playlists")
            compose.onNodeWithText("Duplicate mix").performClick()
            song(playlist.entries[0].id).assertIsNotSelected().performClick().assertIsSelected()
            song(playlist.entries[1].id).assertIsSelected()
            compose.onNodeWithTag("selection-minimized").assert(hasText("2 selected", substring = true))
            song(playlist.entries[1].id).performClick().assertIsNotSelected()
            song(playlist.entries[0].id).assertIsNotSelected()
            compose.onNodeWithTag("selection-minimized").assert(hasText("1 selected", substring = true))
            back()
            tab("Artists")
            compose.onNodeWithText("Shared artist").performClick()
            song(tracks[0].id).assertIsNotSelected()
            song(tracks[1].id).assertIsSelected()
        } finally {
            interfaceStore.edit().clear().commit()
            browseStore.edit().clear().commit()
        }
    }
}
