package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueBrowsingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun queueSearchReturnsToSavedOrderAndDisplaySortDisablesManualMoves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("browse-style", 0)
        preferences.edit().clear().commit()
        val tracks = listOf("Second", "First").map { Track(it, it, fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Mix")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        try {
            compose.setContent { LocalicaTheme { QueueScreen(state, null) } }
            compose.onNode(hasSetTextAction()).performTextInput("First")
            compose.onNodeWithTag("media-${queue.entries[1].id}").assertIsDisplayed()
            compose.onNodeWithTag("media-${queue.entries[0].id}").assertDoesNotExist()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.pressBack() // keyboard
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithTag("media-${queue.entries[0].id}").assertIsDisplayed()
            compose.onNodeWithContentDescription("Sort queue").performClick()
            compose.onNodeWithText("Title").performClick()
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithContentDescription("Actions for Second").performClick()
            compose.onNodeWithText("Move up").assertIsNotEnabled()
            compose.onNodeWithText("Move down").assertIsNotEnabled()
            assertEquals(listOf("Second", "First"), queue.entries.map { it.track.title })
        } finally { preferences.edit().clear().commit() }
    }
}
