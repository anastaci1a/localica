package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class QueueEditsTest {
    private fun song(id: String) = Track(id, id, fileName = "$id.mp3", relativePath = "$id.mp3")
    @Test fun `new queue captures entire ordered result and starts at selected song`() {
        val tracks = mutableListOf(song("z"), song("b"), song("a"))
        val queue = QueueEdits.create(tracks, "b", "Queue")
        tracks.clear()
        assertEquals(listOf("z", "b", "a"), queue.entries.map { it.track.id })
        assertEquals(queue.entries[1].id, queue.currentEntryId)
    }
    @Test fun `repeated songs have distinct entry identities`() {
        val queue = QueueEdits.create(listOf(song("a")), "a", "Queue")
        val edited = QueueEdits.insert(queue, song("a"), SongTap.PlayNow)
        assertEquals(2, edited.entries.size)
        assertNotEquals(edited.entries[0].id, edited.entries[1].id)
        assertEquals(edited.entries[1].id, edited.currentEntryId)
        assertEquals(1, edited.entries.map { it.track.id }.toSet().size)
    }
    @Test fun `add next and append preserve cursor while play now resets it`() {
        val queue = QueueEdits.create(listOf(song("a"), song("b")), "a", "Queue").copy(positionMs = 700)
        val next = QueueEdits.insert(queue, song("c"), SongTap.PlayNext)
        assertEquals(listOf("a", "c", "b"), next.entries.map { it.track.id })
        assertEquals(queue.currentEntryId, next.currentEntryId)
        assertEquals(700L, next.positionMs)
        val appended = QueueEdits.insert(queue, song("c"), SongTap.Append)
        assertEquals(listOf("a", "b", "c"), appended.entries.map { it.track.id })
        assertEquals(0L, QueueEdits.insert(queue, song("c"), SongTap.PlayNow).positionMs)
    }
    @Test fun `removing current entry chooses its successor and handles empty queue`() {
        val queue = QueueEdits.create(listOf(song("a"), song("b"), song("c")), "b", "Queue")
        val removed = QueueEdits.remove(queue, queue.currentEntryId!!)
        assertEquals(queue.entries[2].id, removed.currentEntryId)
        val only = QueueEdits.create(listOf(song("a")), "a", "Queue")
        assertNull(QueueEdits.remove(only, only.currentEntryId!!).currentEntryId)
        val emptied = only.copy(entries = emptyList(), currentEntryId = null)
        assertNotNull(QueueEdits.insert(emptied, song("a"), SongTap.Append).currentEntryId)
    }
    @Test fun `reorder preserves current occurrence and position`() {
        val queue = QueueEdits.create(listOf(song("a"), song("b")), "b", "Queue").copy(positionMs = 500)
        val moved = QueueEdits.move(queue, queue.currentEntryId!!, -1)
        assertEquals(listOf("b", "a"), moved.entries.map { it.track.id })
        assertEquals(queue.currentEntryId, moved.currentEntryId)
        assertEquals(500L, moved.positionMs)
    }
    @Test fun `cap requires enough inactive removals and never permits current removal`() {
        val first = QueueEdits.create(listOf(song("a")), "a", "First").copy(createdAt = 1)
        val second = QueueEdits.create(listOf(song("b")), "b", "Second").copy(createdAt = 2)
        val library = QueueLibrary(listOf(first, second), first.id)
        assertEquals(second.id, QueueEdits.oldestInactive(library))
        assertFalse(QueueEdits.canCreate(library, 2, emptySet()))
        assertFalse(QueueEdits.canCreate(library, 2, setOf(first.id)))
        assertTrue(QueueEdits.canCreate(library, 2, setOf(second.id)))
        assertFalse(QueueEdits.canCreate(library, 1, setOf(second.id)))
        assertTrue(QueueEdits.canCreate(library, null, emptySet()))
    }
}
