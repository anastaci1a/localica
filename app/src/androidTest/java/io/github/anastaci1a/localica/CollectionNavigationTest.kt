package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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
                    playbackContent = { QueueSettingsControls(QueueSettings()) { saved = it(QueueSettings()) } }, initialCategory = "Playback and queues")
            }
        }
        compose.onNodeWithText("Playback and queues").assertIsDisplayed()
        // #696 keeps global search at root; this child only opens its explicit editor.
        val queueLimitEditor = hasSetTextAction() and hasText("Maximum queues (blank = unlimited)")
        compose.onNodeWithText("Search settings").assertDoesNotExist()
        compose.onAllNodes(queueLimitEditor).assertCountEquals(0)
        compose.onNodeWithText("Maximum queues: unlimited").performScrollTo().performClick()
        compose.onAllNodes(queueLimitEditor).assertCountEquals(1)
        val input = compose.onNode(queueLimitEditor)
        input.performTextInput("3")
        input.assertTextContains("3")
        // Activate the real accessible action: a coordinate tap can race the
        // native IME moving this dialog, outside Compose's test clock.
        compose.onNodeWithText("Save queue limit").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(3, saved?.maximum) }
        compose.onAllNodes(queueLimitEditor).assertCountEquals(0)
        compose.onNodeWithText("Search settings").assertDoesNotExist()
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
        compose.onNodeWithText("Album Artists").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MediaKind.AlbumArtist, route.filter) }
    }
}
