package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun drawerArrowMinimizesAndDeletionLivesInActions() {
        var minimized = false
        compose.setContent { LocalicaTheme {
            QueueScreen(QueueUiState(loading = false), null, onMinimize = { minimized = true })
        } }
        compose.onNodeWithContentDescription("Delete current queue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Minimize queue").performClick()
        compose.runOnIdle { assertTrue(minimized) }
        compose.onNodeWithContentDescription("Queue actions").performClick()
        compose.onNodeWithText("Delete", substring = false).assertIsDisplayed()
    }

    @Test fun largeQueueSelectorStartsAtPlaybackAndTruncatesConfirmationNames() {
        val queues = (0..99).map { ListeningQueue(name = "Queue $it with a deliberately very long descriptive name that will not fit", entries = emptyList()) }
        val state = QueueUiState(QueueLibrary(queues, queues[90].id), loading = false)
        compose.setContent { LocalicaTheme { QueueScreen(state, null) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Select queue").performClick()
        // Assert the first frame that exposes panel content, before any settled-state wait.
        for (frame in 0..60) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodesWithText("Playback queue").fetchSemanticsNodes().isNotEmpty()) break
        }
        compose.onNodeWithText("Playback queue").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Remove all other queues").performClick()
        compose.onNodeWithText("Delete queues?").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(queues.first().name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertEquals(1, layouts.single().lineCount)
        assertTrue("Long queue names end in ellipses", layouts.single().isLineEllipsized(0))
    }
    @Test fun currentRowHiddenBySearchPillHasAnUpwardReturnButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("browse-style", 0)
        preferences.edit().clear().commit()
        val tracks = (1..24).map { Track("song:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 2, "Mix")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        try {
            compose.setContent { LocalicaTheme { QueueScreen(state, null) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${queue.entries[2].id}").fetchSemanticsNodes().isNotEmpty() }
            val current = compose.onNodeWithTag("media-${queue.entries[2].id}")
            val pill = compose.onNodeWithTag("list-search-pill")
            current.assertIsDisplayed()
            val distance = current.fetchSemanticsNode().boundsInRoot.bottom - pill.fetchSemanticsNode().boundsInRoot.bottom + 24f
            compose.onNodeWithTag("browser-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
            val button = compose.onNodeWithContentDescription("Current song")
            button.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll up"))
            button.performTouchInput { click() }
            compose.waitUntil(5000) {
                current.fetchSemanticsNode().boundsInRoot.top >= pill.fetchSemanticsNode().boundsInRoot.bottom
            }
            button.assertDoesNotExist()
            assertEquals(queue.entries[2].id, queue.currentEntryId)
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun queueSearchReturnsToSavedOrderAndDisplaySortDisablesManualMoves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("browse-style", 0)
        preferences.edit().clear().commit()
        val tracks = listOf("Second", "First").map { Track(it, it, fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Mix")
        val other = QueueEdits.create(tracks.reversed(), 0, "Mix")
        val state = QueueUiState(QueueLibrary(listOf(queue, other), queue.id), loading = false)
        try {
            compose.setContent { LocalicaTheme { QueueScreen(state, null) } }
            compose.onNodeWithText("1. Mix").assertIsDisplayed()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(context.getExternalFilesDir(null), "queue-header.png"))
            compose.onNodeWithContentDescription("Select queue").performClick()
            compose.onNodeWithText("2. Mix").assertIsDisplayed()
            compose.onNodeWithText("Playback queue").assertIsDisplayed()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(context.getExternalFilesDir(null), "queue-selector.png"))
            pressSystemBack()
            compose.onNodeWithContentDescription("Save queue to playlist").performClick()
            compose.onNodeWithText("New playlist name").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("First")
            compose.onNodeWithTag("media-${queue.entries[1].id}").assertIsDisplayed()
            compose.onNodeWithTag("media-${queue.entries[0].id}").assertDoesNotExist()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty() }
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("media-${queue.entries[0].id}").assertIsDisplayed()
            compose.onNodeWithContentDescription("Sort queue").performClick()
            compose.onNodeWithText("Sort by").performClick()
            compose.onNodeWithText("Title").performClick()
            compose.onNodeWithText("Select", substring = false).performClick()
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithContentDescription("Actions for Second").performClick()
            compose.onNodeWithText("Move up").assertIsNotEnabled()
            compose.onNodeWithText("Move down").assertIsNotEnabled()
            assertEquals(listOf("Second", "First"), queue.entries.map { it.track.title })
        } finally { preferences.edit().clear().commit() }
    }
}
