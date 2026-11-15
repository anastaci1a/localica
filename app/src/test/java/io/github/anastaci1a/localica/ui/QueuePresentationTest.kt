package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

class QueuePresentationTest {
    private fun track(id: String, duration: Long? = null) = Track(id, id, durationMs = duration, fileName = id, relativePath = id)
    private fun queue(id: String, entries: List<QueueEntry>, current: String? = entries.firstOrNull()?.id) =
        ListeningQueue(id = id, name = id, entries = entries, currentEntryId = current)

    private class Counted<T>(private val source: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = source.size
        override fun get(index: Int): T { reads++; return source[index] }
    }

    @Test fun duplicateCatalogOccurrenceAndLibraryIdsKeepFirstMatchAndOccurrenceDuration() {
        val saved = track("song", 7).copy(playCount = 90)
        val first = track("song", 11).copy(title = "First", playCount = 4)
        val last = first.copy(title = "Last")
        val active = queue("active", listOf(QueueEntry("same", saved), QueueEntry("same", last)), "same")
        val browsed = queue("browsed", listOf(QueueEntry("b", saved), QueueEntry("c", track("unknown"))), "absent")
        val source = QueueUiState(QueueLibrary(listOf(active, active.copy(name = "Duplicate"), browsed), active.id), browsedId = browsed.id)
        val read = prepareQueuePresentation(source, LibrarySnapshot(tracks = listOf(first, last)), null)
        assertSame(active, read.active!!.queue)
        assertSame(browsed, read.browsed!!.queue)
        assertEquals(3, read.number)
        assertEquals(0, read.active.index)
        assertEquals(-1, read.browsed.index)
        assertEquals(18L, read.active.duration)
        assertEquals(7L, read.browsed.duration)
        assertEquals(PlayerTrackProjection(saved, first, first), read.tracks)
        val fallback = prepareQueuePresentation(source.copy(browsedId = "missing"), LibrarySnapshot(trackPlays = mapOf(saved.id to 9)), read)
        assertSame(fallback.active, fallback.browsed)
        assertEquals(1, fallback.number)
        assertEquals(PlayerTrackProjection(saved, saved.copy(playCount = 9), saved), fallback.tracks)
    }

    @Test fun cursorAndStatisticsCopiesReuseTheUnchangedSources() {
        val saved = track("song", 1000)
        val entries = Counted(listOf(QueueEntry("one", saved), QueueEntry("two", saved)))
        val tracks = Counted(listOf(saved.copy(playCount = 4)))
        val original = queue("queue", entries, "two")
        val library = LibrarySnapshot(tracks = tracks)
        val source = QueueUiState(QueueLibrary(listOf(original), original.id))
        val initial = prepareQueuePresentation(source, library, null)
        entries.reads = 0
        tracks.reads = 0
        val position = prepareQueuePresentation(source.copy(library = source.library.update(original.copy(positionMs = 9000))), library, initial)
        assertSame(initial.tracks, position.tracks)
        assertEquals(0, entries.reads)
        assertEquals(0, tracks.reads)
        val counts = prepareQueuePresentation(position.state, library.copy(trackPlays = mapOf(saved.id to 99)), position)
        assertSame(initial.available, counts.available)
        assertEquals(0, entries.reads)
        assertEquals(0, tracks.reads)
        val cursor = prepareQueuePresentation(source.copy(library = source.library.update(original.copy(currentEntryId = "one"))), library, counts)
        assertEquals(0, cursor.active!!.index)
        assertEquals(2000L, cursor.active.duration)
        assertEquals(0, tracks.reads)
        assertEquals(2, entries.reads)
    }

    @Test fun emptyUnknownNegativeAndOverflowDurationMatchTheLegacyNullableSum() {
        for (durations in listOf(emptyList(), listOf(null), listOf(0L, null, -4L), listOf(Long.MAX_VALUE, 7L))) {
            val entries = durations.mapIndexed { i, duration -> QueueEntry("$i", track("$i", duration)) }
            val queue = queue("queue", entries)
            val source = QueueUiState(QueueLibrary(listOf(queue), queue.id))
            val read = prepareQueuePresentation(source, LibrarySnapshot(), null)
            assertEquals(durations.filterNotNull().takeIf { it.isNotEmpty() }?.sum(), read.browsed!!.duration)
            assertEquals(entries.indexOfFirst { it.id == queue.currentEntryId }, read.browsed.index)
        }
        val absent = prepareQueuePresentation(QueueUiState(QueueLibrary(listOf(queue("q", emptyList())), "missing")), LibrarySnapshot(), null)
        assertNull(absent.active)
        assertNull(absent.browsed)
        assertEquals(0, absent.number)
        assertEquals(PlayerTrackProjection(null, null, null), absent.tracks)
    }
}
