package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerTrackProjectionTest {
    @get:Rule val compose = createComposeRule()
    private fun track(id: String, title: String = id) = Track("content://$id", title,
        fileName = "$id.mp3", relativePath = "$id.mp3", id = id)

    private class Counted<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }

    @Test fun positionOnlyPublicationsReuseOccurrenceAndLibraryLookups() {
        val tracks = Counted(List(13_313) { track("$it") })
        val saved = track("13312", "Saved")
        val entries = Counted(List(100) { QueueEntry("entry-$it", saved) })
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = entries, currentEntryId = "entry-99")
        val state = mutableStateOf(QueueLibrary(listOf(queue), queue.id))
        val library = LibrarySnapshot(tracks = tracks)
        var publishedPosition = -1L
        var observed: PlayerTrackProjection? = null
        compose.setContent {
            val current = state.value
            val result = rememberPlayerTrackProjection(current, library)
            SideEffect { observed = result; publishedPosition = current.active!!.positionMs }
        }
        compose.waitForIdle()
        val initial = requireNotNull(observed)
        compose.runOnIdle { tracks.reads = 0; entries.reads = 0 }
        repeat(10) { index ->
            val position = (index + 1) * 250L
            compose.runOnIdle { state.value = QueueLibrary(listOf(queue.copy(positionMs = position)), queue.id) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(position, publishedPosition)
                assertSame(initial, observed)
                assertEquals("No repeated library scan during position updates", 0, tracks.reads)
                assertEquals("No repeated occurrence scan during position updates", 0, entries.reads)
            }
        }
        assertEquals("13312", initial.indexed!!.title)
        assertEquals("Saved", initial.current!!.title)
    }

    @Test fun occurrenceAndMetadataChangesPreserveFirstMatchAndDistinctUnavailableFallbacks() {
        val savedA = track("same", "Saved A").copy(playCount = 90)
        val savedB = track("same", "Saved B").copy(playCount = 80)
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = listOf(
            QueueEntry("a", savedA), QueueEntry("b", savedB)), currentEntryId = "a")
        val queues = mutableStateOf(QueueLibrary(listOf(queue), queue.id))
        val library = mutableStateOf(LibrarySnapshot(trackPlays = mapOf("same" to 4L)))
        var observed: PlayerTrackProjection? = null
        compose.setContent {
            val result = rememberPlayerTrackProjection(queues.value, library.value)
            SideEffect { observed = result }
        }
        fun assertProjection(current: Track?, indexed: Track?, lyrics: Track?) {
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(PlayerTrackProjection(current, indexed, lyrics), observed) }
        }
        assertProjection(savedA, savedA.copy(playCount = 4), savedA)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue.copy(currentEntryId = "b")), queue.id) }
        assertProjection(savedB, savedB.copy(playCount = 4), savedB)
        val first = track("same", "First available").copy(playCount = 7)
        val last = first.copy(title = "Last duplicate")
        compose.runOnIdle { library.value = LibrarySnapshot(tracks = listOf(first, last)) }
        assertProjection(savedB, first, first)
        val edited = first.copy(title = "Edited", durationMs = 1234)
        compose.runOnIdle { library.value = library.value.copy(tracks = listOf(edited, last)) }
        assertProjection(savedB, edited, edited)
        compose.runOnIdle { library.value = LibrarySnapshot(trackPlays = mapOf("same" to 9L)) }
        assertProjection(savedB, savedB.copy(playCount = 9), savedB)
        compose.runOnIdle { library.value = LibrarySnapshot() }
        assertProjection(savedB, savedB.copy(playCount = 0), savedB)
        // Malformed duplicate occurrence/queue identities retain the old first-match rules.
        val duplicates = queue.copy(entries = listOf(QueueEntry("b", savedA), QueueEntry("b", savedB)), currentEntryId = "b")
        compose.runOnIdle { queues.value = QueueLibrary(listOf(duplicates, queue.copy(currentEntryId = "b")), queue.id) }
        assertProjection(savedA, savedA.copy(playCount = 0), savedA)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue.copy(currentEntryId = "absent")), queue.id) }
        assertProjection(null, null, null)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue), "absent") }
        assertProjection(null, null, null)
    }
}
