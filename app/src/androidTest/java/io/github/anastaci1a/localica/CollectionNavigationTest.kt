package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.navigation.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("missing", "Repeated", fileName = "tone.mp3", relativePath = "tone.mp3")

    @Test fun queueLimitInputOpensOnlyForExplicitEditing() {
        var saved: QueueSettings? = null
        compose.setContent {
            LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {},
                    playbackContent = { QueueSettingsControls(QueueSettings()) { saved = it } }, initialCategory = "Playback and queues")
            }
        }
        compose.onNodeWithText("Playback and queues").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Maximum queues: unlimited").performScrollTo().performClick()
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performTextInput("3")
        compose.onNodeWithText("Save queue limit").performClick()
        compose.runOnIdle { assertEquals(3, saved?.maximum) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun albumGroupsAndViewChoicesAreReachable() {
        val tagged = track.copy(album = "Compilation", albumArtist = "Various Artists")
        val snapshot = LibrarySnapshot(tracks = listOf(tagged))
        var opened: String? = null
        var route by mutableStateOf(BrowseRoute(MainTab.Library, filter = MediaKind.Album))
        compose.setContent {
            LocalicaTheme {
                BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                    { route = it }, { opened = it.ref.id }, {}, { _, _, _ -> }, null, null, true, false)
            }
        }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Compilation"))
        compose.onNodeWithText("Compilation").performClick()
        compose.runOnIdle { assertNotNull(opened) }
        compose.onNodeWithText("Album artists").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MediaKind.AlbumArtist, route.filter) }
    }
}
