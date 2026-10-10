package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyLibraryActionsRemainReachableInShortWindow() {
        var opened = false
        compose.setContent {
            LocalicaTheme {
                Box(Modifier.requiredSize(360.dp, 220.dp)) {
                    BrowserScreen(LibraryUiState(loading = false), PlaylistState(loading = false), BrowseRoute(MainTab.Songs),
                        {}, {}, { opened = true }, { _, _, _ -> }, null, null, true, false)
                }
            }
        }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Add music folders"))
        compose.onNodeWithText("Add music folders").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun folderRemovalRequiresConfirmationAndDoesNotRemoveFiles() {
        var removed: String? = null
        compose.setContent {
            LocalicaTheme {
                FolderSettings(LibraryUiState(snapshot = LibrarySnapshot(listOf(FolderSelection("tree", "Music"))), loading = false),
                    onDismiss = {}, onChooseFolder = {}, onRescan = {}, onCancel = {}, onRemove = { removed = it })
            }
        }
        compose.onNodeWithText("Library and tags").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove folder?").assertIsDisplayed()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Remove").performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.runOnIdle { assertEquals("tree", removed) }
    }

    @Test fun tabsSelectScrollableLibraryAndSettingsOpenFolderConfiguration() {
        val tracks = (1..40).map { Track("track:$it", "Track $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var selected: Track? = null
        var requestedFolder = false
        compose.setContent {
            LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(listOf(FolderSelection("tree", "Music")), tracks), loading = false),
                    PlaybackState(connected = true), onPlay = { selected = it }, onToggle = {}, onSeek = {},
                    onChooseFolder = { requestedFolder = true }, onRescan = {}, onCancelScan = {})
            }
        }
        compose.onNodeWithText("Your music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Songs").performClick()
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Track 40"))
        compose.onNodeWithText("Track 40").performClick()
        compose.runOnIdle { assertEquals(tracks.last(), selected) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Library and tags").performClick()
        compose.onNodeWithText("Music folders").assertIsDisplayed()
        compose.onNodeWithText("Add folder").performClick()
        compose.runOnIdle { assertTrue(requestedFolder) }
    }
}
