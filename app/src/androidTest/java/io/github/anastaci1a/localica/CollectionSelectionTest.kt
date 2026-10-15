package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test

class CollectionSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playlistCatalogSelectionRemainsSelectedAndRemovableInItsDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interfaceStore = context.getSharedPreferences("interface", 0)
        val browseStore = context.getSharedPreferences("browse-style", 0)
        interfaceStore.edit().clear().commit()
        browseStore.edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Playlists), reducedMotion = true))
        val track = Track("selection:song", "Repeated song", fileName = "song.mp3", relativePath = "song.mp3")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Selection mix"), listOf(track, track))
        try {
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                    playlist = PlaylistState(listOf(playlist), loading = false))
            } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-Playlist:${playlist.id}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Actions for Selection mix").performClick()
            compose.onNodeWithText("Select all songs").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithText("Remove from playlist").assertExists()
            // Selection appears in the underlying activity before the closing menu's
            // native window is necessarily removed. Wait for that explicit handoff
            // before injecting the next physical touch into the activity.
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty()
            }
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("media-Playlist:${playlist.id}"))
            compose.onNodeWithTag("media-Playlist:${playlist.id}")
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-${playlist.entries.first().id}").fetchSemanticsNodes().isNotEmpty()
            }
            val first = compose.onNodeWithTag("media-${playlist.entries.first().id}")
            val second = compose.onNodeWithTag("media-${playlist.entries.last().id}")
            first.assertIsSelected()
            second.assertIsSelected()
            first.performClick()
            first.assertIsNotSelected()
            second.assertIsSelected()
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onNodeWithText("Remove from playlist").assertExists()
        } catch (failure: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("CollectionSelection")
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            interfaceStore.edit().clear().commit()
            browseStore.edit().clear().commit()
        }
    }
}
