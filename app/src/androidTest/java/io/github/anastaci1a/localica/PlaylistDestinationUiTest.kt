package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.room.withTransaction
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
    @get:Rule val compose = createComposeRule<androidx.activity.ComponentActivity>()
    private var contentMounted by mutableStateOf(true)

    @org.junit.After fun retireEditorBeforeActivityDisposal() {
        // These fixtures deliberately finish with an active draft. Dispose its
        // input while the Activity still owns a live native window (#622).
        val input = compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        compose.runOnIdle { contentMounted = false }
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            input.hideSoftInputFromWindow(view.windowToken, 0)
            input.restartInput(view)
        }
        compose.waitUntil(5000) {
            val retired = compose.runOnUiThread {
                !input.isAcceptingText && androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
            }
            retired && nativeImeWindowVisible(android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys -t 2 input_method")
            ).bufferedReader().use { it.readText() }) == false
        }
    }

    @Test fun playlistNameOmitsSearchIconAndItsSlotWhileKeepingClearAndFocus() {
        compose.setContent { if (contentMounted) LocalicaTheme {
            AddToPlaylistDialog(emptyList(), PlaylistState(loading = false), null, {}, {})
        } }
        val input = compose.onNodeWithContentDescription("New playlist name input")
        input.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("glass-search-icon", useUnmergedTree = true).assertDoesNotExist()
        val fieldBounds = input.fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithText("New playlist name", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("No empty leading-icon slot remains", with(compose.density) { 16.dp.toPx() },
            labelBounds.left - fieldBounds.left, 1f)

        input.performTextInput("Draft")
        compose.onNodeWithContentDescription("Clear text").performClick()
        input.assertIsFocused().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("Clear text").assertDoesNotExist()
        input.performTextInput("Another draft")
        input.assertTextEquals("Another draft")
        compose.onNodeWithTag("glass-search-icon", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun actualSearchKeepsItsLeadingIconByDefault() {
        compose.setContent { if (contentMounted) LocalicaTheme {
            GlassSearchField("", {}, "Search songs")
        } }
        compose.onNodeWithContentDescription("Search songs input").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("glass-search-icon", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun backFromNameEndsEditingBeforeDismissingPicker() {
        var closed by mutableStateOf(false)
        compose.setContent { if (contentMounted) LocalicaTheme {
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
        val releaseSave = CompletableDeferred<Unit>()
        var finished = false
        try {
            runBlocking {
                controller.state.first { !it.loading }
                val created = CompletableDeferred<Boolean>()
                controller.create("Existing") { created.complete(it) }
                assertTrue(withTimeout(5000) { created.await() })
            }
            compose.setContent { if (contentMounted) LocalicaTheme {
                val state by controller.state.collectAsState()
                AddToPlaylistDialog(listOf(song), state, controller, {}, { finished = true })
            } }
            compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("Existing", substring = false).performClick()
            compose.onNodeWithText("Add", substring = false).assertIsEnabled()
            compose.onNodeWithText("New playlist name").performTextInput(" existing ")
            compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextReplacement("New mix")

            // Keep the real write pending so the dialog's disabled state is observable.
            val transactionEntered = CompletableDeferred<Unit>()
            scope.launch(Dispatchers.IO) {
                database.withTransaction {
                    transactionEntered.complete(Unit)
                    releaseSave.await()
                }
            }
            runBlocking { withTimeout(5000) { transactionEntered.await() } }
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithContentDescription("New playlist name input").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Clear text").assertIsNotEnabled()
            compose.onNodeWithText("Existing", substring = false).assertIsNotEnabled().assertIsSelected()
            compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("Cancel", substring = false).assertIsNotEnabled()
            compose.runOnIdle { assertFalse(finished) }
            releaseSave.complete(Unit)
            compose.waitUntil(5000) { finished }
            compose.runOnIdle {
                val playlists = controller.state.value.playlists
                assertEquals(listOf("Existing", "New mix"), playlists.map { it.name })
                playlists.forEach { assertEquals(listOf(song.id), it.entries.map { entry -> entry.track.id }) }
            }
        } finally { releaseSave.complete(Unit); scope.cancel(); database.close() }
    }
}
