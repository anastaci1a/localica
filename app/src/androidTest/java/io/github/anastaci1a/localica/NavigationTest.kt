package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsSelectScrollableLibraryAndSettingsOpenFolderConfiguration() {
        val tracks = (1..40).map { Track("track:$it", "Track $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var selected: Track? = null
        var requestedFolder = false
        compose.setContent {
            LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(FolderSelection("tree", "Music"), tracks), loading = false),
                    PlaybackState(connected = true), onPlay = { selected = it }, onToggle = {}, onSeek = {},
                    onChooseFolder = { requestedFolder = true }, onRescan = {}, onCancelScan = {})
            }
        }
        compose.onNodeWithText("Nothing playing").assertIsDisplayed()
        val playerCenter = compose.onNodeWithText("Player").fetchSemanticsNode().boundsInRoot.center.x
        val libraryCenter = compose.onNodeWithText("Library").fetchSemanticsNode().boundsInRoot.center.x
        val settingsCenter = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(playerCenter < libraryCenter && libraryCenter < settingsCenter)
        compose.onNodeWithText("Library").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Track 40"))
        compose.onNodeWithText("Track 40").performClick()
        compose.runOnIdle { assertEquals(tracks.last(), selected) }
        compose.onNodeWithText("Nothing playing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Music folder").assertIsDisplayed()
        compose.onNodeWithText("Change folder").performClick()
        compose.runOnIdle { assertTrue(requestedFolder) }
    }
}
