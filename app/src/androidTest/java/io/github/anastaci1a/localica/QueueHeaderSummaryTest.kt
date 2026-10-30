package io.github.anastaci1a.localica

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test

class QueueHeaderSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentOccurrenceMetadataAndBrowsedQueueKeepTheirOwnSummary() {
        val first = Track("summary:first", "First", durationMs = 61000, fileName = "first.mp3", relativePath = "first.mp3")
        val second = Track("summary:second", "Second", durationMs = null, fileName = "second.mp3", relativePath = "second.mp3")
        val entries = listOf(QueueEntry("one", first), QueueEntry("two", second), QueueEntry("three", first))
        val queue = ListeningQueue(id = "summary", name = "Summary", entries = entries, currentEntryId = "three")
        val state = mutableStateOf(QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false))
        compose.setContent {
            LocalicaTheme {
                QueueScreen(state.value, null,
                    library = LibraryUiState(LibrarySnapshot(tracks = listOf(first, second)), loading = false),
                    reducedMotion = true)
            }
        }
        fun summary(position: String, duration: String) {
            compose.onNodeWithText(position).assertExists()
            compose.onNode(hasText(duration) and hasAnySibling(hasText(position))).assertExists()
        }
        fun update(next: ListeningQueue) {
            compose.runOnIdle { state.value = state.value.copy(library = state.value.library.update(next)) }
        }
        summary("3 / 3", "2:02")
        // Cursor-only publications retain the exact entries, but must refresh occurrence position.
        update(queue.copy(currentEntryId = "two", positionMs = 9000))
        summary("2 / 3", "2:02")
        update(queue.copy(currentEntryId = "absent"))
        summary("0 / 3", "2:02")
        val metadata = queue.copy(entries = entries.map {
            it.copy(track = it.track.copy(durationMs = if (it.id == "two") 1000 else 120000))
        }, currentEntryId = "two")
        update(metadata)
        summary("2 / 3", "4:01")
        val unknown = metadata.copy(entries = metadata.entries.map { it.copy(track = it.track.copy(durationMs = null)) })
        update(unknown)
        summary("2 / 3", "–:––")
        update(unknown.copy(entries = emptyList(), currentEntryId = null))
        summary("0 / 0", "–:––")
        val other = ListeningQueue(id = "other-summary", name = "Other summary",
            entries = listOf(QueueEntry("other", first.copy(durationMs = 5000))))
        compose.runOnIdle {
            state.value = state.value.copy(library = QueueLibrary(listOf(queue, other), queue.id), browsedId = other.id)
        }
        summary("1 / 1", "0:05")
        compose.runOnIdle { state.value = state.value.copy(browsedId = queue.id) }
        summary("3 / 3", "2:02")
        compose.runOnIdle { state.value = QueueUiState(loading = false) }
        summary("0 / 0", "0:00")
    }
}
