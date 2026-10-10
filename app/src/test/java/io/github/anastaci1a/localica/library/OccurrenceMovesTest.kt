package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class OccurrenceMovesTest {
    @Test fun nonconsecutiveSelectionsKeepSourceOrderRegardlessOfSelectionOrder() {
        val items = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(listOf("a", "c", "e", "b", "d", "f"), OccurrenceMoves.before(items, linkedSetOf("d", "b"), "f") { it })
        assertEquals(listOf("b", "d", "a", "c", "e", "f"), OccurrenceMoves.before(items, setOf("b", "d"), "a") { it })
        assertEquals(listOf("a", "c", "e", "f", "b", "d"), OccurrenceMoves.before(items, setOf("b", "d"), null) { it })
        assertEquals(items, OccurrenceMoves.before(items, items.toSet(), null) { it })
    }
    @Test fun movingDuplicateOccurrencesRetainsQueueCursorAndPlaylistIdentity() {
        val song = Track("uri", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val queue = QueueEdits.create(List(5) { song }, 2, "Queue").copy(positionMs = 4500)
        val ids = setOf(queue.entries[1].id, queue.entries[3].id)
        val moved = QueueEdits.moveBefore(queue, ids, queue.entries[0].id)
        assertEquals(listOf(1, 3, 0, 2, 4).map { queue.entries[it].id }, moved.entries.map { it.id })
        assertEquals(queue.currentEntryId, moved.currentEntryId)
        assertEquals(4500L, moved.positionMs)
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "List"), List(5) { song })
        val edited = PlaylistEdits.moveBefore(playlist, setOf(playlist.entries[1].id, playlist.entries[3].id), null)
        assertEquals(listOf(0, 2, 4, 1, 3).map { playlist.entries[it].id }, edited.entries.map { it.id })
    }
    @Test fun missingOrSelectedInsertionAnchorsRejectStaleMoves() {
        for (anchor in listOf("gone", "b")) try {
            OccurrenceMoves.before(listOf("a", "b"), setOf("b"), anchor) { it }
            fail("Invalid anchor must not silently append")
        } catch (_: IllegalArgumentException) { }
    }
}
