package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistDestinationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backFromNameEndsEditingBeforeDismissingPicker() {
        var closed by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            if (!closed) AddToPlaylistDialog(emptyList(), PlaylistState(loading = false), null, { closed = true }, {})
        } }
        compose.onNodeWithText("New playlist name").performClick().performTextInput("Draft")
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Add to playlists").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { assertFalse(closed) }
    }

    @Test fun duplicateDraftCannotAddAndKeyboardSubmissionCommitsEveryDestination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        val song = Track("test:song", "Example", fileName = "example.mp3", relativePath = "example.mp3")
        var finished = false
        try {
            runBlocking {
                controller.state.first { !it.loading }
                val created = CompletableDeferred<Boolean>()
                controller.create("Existing") { created.complete(it) }
                assertTrue(withTimeout(5000) { created.await() })
            }
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                AddToPlaylistDialog(listOf(song), state, controller, {}, { finished = true })
            } }
            compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("Existing", substring = false).performClick()
            compose.onNodeWithText("Add", substring = false).assertIsEnabled()
            compose.onNodeWithText("New playlist name").performTextInput(" existing ")
            compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextReplacement("New mix")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { finished }
            compose.runOnIdle {
                val playlists = controller.state.value.playlists
                assertEquals(listOf("Existing", "New mix"), playlists.map { it.name })
                playlists.forEach { assertEquals(listOf(song.id), it.entries.map { entry -> entry.track.id }) }
            }
        } finally { scope.cancel(); database.close() }
    }
}
