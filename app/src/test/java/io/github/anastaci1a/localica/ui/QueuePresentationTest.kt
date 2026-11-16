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

    @Test fun publishedCommandSourcesResolveFirstMatchesWithoutReadingOccurrences() {
        val entries = Counted(listOf(QueueEntry("entry", track("song"))))
        val active = queue("active", entries, "entry")
        val browsed = queue("browsed", entries, "entry")
        val catalog = Counted(listOf(active, active.copy(name = "Duplicate"), browsed, browsed.copy(entries = emptyList())))
        entries.reads = 0
        val source = QueueUiState(QueueLibrary(catalog, active.id), browsedId = browsed.id).resolvePlayerQueue()
        val resolved = source.resolvedPlayerQueue!!
        assertSame(source.library, resolved.library)
        assertEquals(browsed.id, resolved.browsedId)
        assertSame(active, resolved.active)
        assertSame(browsed, resolved.browsed)
        assertEquals(3, resolved.number)
        assertEquals(0, entries.reads)
        catalog.reads = 0
        val ready = prepareQueuePresentation(source, LibrarySnapshot(), null)
        assertEquals("Preparation reuses the published catalog answer", 0, catalog.reads)
        assertSame(browsed, ready.browsed!!.queue)
        val fallback = prepareQueuePresentation(source.copy(browsedId = "missing"), LibrarySnapshot(), ready)
        assertSame(active, fallback.browsed!!.queue)
        assertEquals(1, fallback.number)
        val absent = prepareQueuePresentation(source.copy(library = QueueLibrary()), LibrarySnapshot(), ready)
        assertNull(absent.active)
        assertNull(absent.browsed)
        assertEquals(0, absent.number)
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

    @Test fun occurrenceAdmissionSurvivesUnrelatedPublicationsAndRejectsEveryChangedOwner() {
        val entries = Counted(listOf(QueueEntry("one", track("song")), QueueEntry("two", track("song"))))
        val tracks = Counted(listOf(track("song").copy(title = "Available")))
        val queue = queue("active", entries, "one")
        val library = LibrarySnapshot(tracks = tracks)
        val source = QueueUiState(QueueLibrary(listOf(queue), queue.id)).resolvePlayerQueue()
        val ready = prepareQueuePresentation(source, library, null)
        val position = source.copy(library = source.library.update(queue.copy(positionMs = 9000))).resolvePlayerQueue()
        val cursor = source.copy(library = source.library.update(queue.copy(currentEntryId = "two"))).resolvePlayerQueue()
        val replacement = source.copy(library = source.library.update(queue.copy(entries = entries.toList()))).resolvePlayerQueue()
        val active = source.copy(library = QueueLibrary(listOf(queue, queue.copy(id = "other")), "other")).resolvePlayerQueue()
        val duplicate = source.copy(library = QueueLibrary(listOf(queue.copy(entries = emptyList()), queue), queue.id)).resolvePlayerQueue()
        entries.reads = 0
        tracks.reads = 0
        assertSame(ready.tracks, ready.playerTracks(source.copy(settings = source.settings.copy(preloadNext = false)), library))
        assertSame(ready.tracks, ready.playerTracks(source.copy(browsedId = "other"), library))
        assertSame(ready.tracks, ready.playerTracks(position, library))
        assertNull(ready.playerTracks(source.copy(library = position.library), library)) // A copied proof cannot authorize another library.
        assertNull(ready.playerTracks(cursor, library))
        assertNull(ready.playerTracks(replacement, library))
        assertNull(ready.playerTracks(active, library))
        assertNull(ready.playerTracks(duplicate, library))
        assertNull(ready.playerTracks(source, library.copy(tracks = listOf(track("song")))))
        assertNull(ready.playerTracks(source, library.copy(trackPlays = mapOf("song" to 1))))
        assertEquals(0, entries.reads)
        assertEquals(0, tracks.reads)
    }

    @Test fun preparedArtworkWindowsAlwaysResolveTheirOwnSourceOrdinals() {
        val original = queue("artwork", List(140) { QueueEntry("entry-$it", track("song-$it")) }, "entry-70")
        for (shuffle in listOf(false, true)) for (loop in listOf(false, true)) for (reshuffle in listOf(false, true)) {
            val modes = JourneyModes(shuffle, loop, reshuffle)
            val prepared = original.copy(journey = QueueJourney.start(original.ids, original.currentEntryId, modes, kotlin.random.Random(1)))
            val edited = prepared.copy(entries = original.entries.reversed().map { it.copy(track = it.track.copy(uri = it.track.uri + "?edited")) })
            val repaired = edited.copy(journey = edited.journey!!.reconcile(edited.ids, edited.currentEntryId, modes, kotlin.random.Random(2)))
            for (queue in listOf(prepared, prepared.copy(positionMs = 9000), repaired)) {
                val journey = queue.journey!!
                assertTrue(journey.artworkWindow().isNotEmpty())
                for (visit in journey.window()) {
                    val ordinal = journey.sourceOrdinal(queue.ids, visit.entryId)
                    assertNotNull("Every exposed visit owns a source ordinal", ordinal)
                    assertEquals(visit.entryId, queue.entries[ordinal!!].id)
                }
                val expected = journey.artworkWindow().map { visit -> queue.entries.first { it.id == visit.entryId }.track.uri }.distinct()
                assertEquals(expected, queue.artworkPreloadUris())
                val neighbors = listOf(journey.next, journey.previous).map { visit -> queue.entries.firstOrNull { it.id == visit?.entryId }?.track?.uri }
                assertEquals(neighbors, queue.artworkNeighbors())
            }
        }
    }
}
