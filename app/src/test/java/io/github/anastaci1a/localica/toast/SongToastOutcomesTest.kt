package io.github.anastaci1a.localica.toast

import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEntry
import org.junit.Assert.*
import org.junit.Test

class SongToastOutcomesTest {
    private val track = Track("content://song", "Song", fileName = "song.flac", relativePath = "song.flac")

    @Test fun duplicateTrackAppendTargetsNewOccurrenceAndKeepsExistingQueue() {
        val old = QueueEntry("old", track)
        val added = QueueEntry("new", track)
        val before = ListeningQueue(id = "queue", name = "Queue", entries = listOf(old))
        val toast = SongToastOutcomes.queue(before, before.copy(entries = listOf(old, added)), false)!!
        assertEquals(ToastCommand.OpenQueue("queue", "new", false), toast.tap)
        assertEquals("Song added to queue", toast.message)
        assertEquals(listOf(track.uri), toast.artworkUris)
        assertNull(SongToastOutcomes.queue(before, before, false))
    }

    @Test fun playNextTargetsCurrentQueueWithDistinctCommandAndStackedCovers() {
        val before = ListeningQueue(id = "queue", name = "Queue", entries = listOf(QueueEntry("current", track)))
        val added = (1..4).map { QueueEntry("next-$it", track.copy(uri = "content://song/$it")) }
        val after = before.copy(entries = before.entries + added)
        val toast = SongToastOutcomes.queue(before, after, true)!!
        assertEquals(ToastCommand.OpenQueue("queue", "next-1", true), toast.tap)
        assertEquals("4 songs playing next", toast.message)
        assertEquals(3, toast.artworkUris.size)
        assertNotEquals(toast.coalescingKey, SongToastOutcomes.queue(before, after, false)!!.coalescingKey)
    }

    @Test fun existingAndNewListBothTargetFirstAddedOccurrence() {
        val before = ManualPlaylist(id = "existing", name = "Existing", entries = listOf(SongRow("old", track)))
        val changed = before.copy(entries = before.entries + listOf(SongRow("added", track), SongRow("added-2", track)))
        val new = ManualPlaylist(id = "new", name = "New list", entries = listOf(SongRow("new-first", track)))
        val toasts = SongToastOutcomes.lists(listOf(before), listOf(changed, new))
        assertEquals(listOf(ToastCommand.OpenList("existing", "added"), ToastCommand.OpenList("new", "new-first")), toasts.map { it.tap })
        assertEquals(listOf("2 songs added to Existing", "Song added to New list"), toasts.map { it.message })
        assertTrue(SongToastOutcomes.lists(listOf(before), listOf(before)).isEmpty())
    }

    @Test fun unrelatedQueueSnapshotsCannotGenerateWrongDestination() {
        assertThrows(IllegalArgumentException::class.java) {
            SongToastOutcomes.queue(ListeningQueue(id = "a", name = "A", entries = emptyList()), ListeningQueue(id = "b", name = "B", entries = emptyList()), false)
        }
    }
}
