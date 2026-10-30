package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.browse.BrowseQueries
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.QueueEdits
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LibraryTrackLookupTest {
    private fun track(id: String) = Track("content://library/$id", "Song $id",
        fileName = "$id.mp3", relativePath = "$id.mp3", id = id)

    private class CountedTracks(private val values: List<Track>) : AbstractList<Track>() {
        val reads = AtomicInteger()
        override val size get() = values.size
        override fun get(index: Int): Track {
            reads.incrementAndGet()
            return values[index]
        }
    }

    @Test fun playlistAndBrowseQueriesShareOneLookupWithoutRescanningTheLibrary() {
        val values = (0 until 13_313).map { track("$it") }
        val counted = CountedTracks(values)
        val snapshot = LibrarySnapshot(tracks = counted)
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Repeated song"),
            listOf(values[50], values[50], values[9]))
        val queue = QueueEdits.create(listOf(values[50], values[50], values[9]), 1, "Queue")
        val query = LibraryQuery(sort = TrackSort.Manual)
        assertEquals(0, counted.reads.get())
        repeat(3) {
            assertEquals(playlist.entries.map { it.id }, PlaylistEdits.rows(playlist, snapshot, query).map { it.id })
            val opened = requireNotNull(BrowseQueries.openingQueue(snapshot, queue,
                BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id)), queue.entries[1].id))
            assertEquals(queue.entries.map { it.id }, opened.rows.map { it.id })
            val detail = BrowseQueries.evaluate(snapshot, listOf(playlist),
                BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)), query)
            assertEquals(playlist.entries.map { it.id }, detail.rows.map { it.id })
        }
        assertEquals("One source traversal across independent consumers", values.size, counted.reads.get())
    }

    @Test fun concurrentFirstReadersPublishOneCompleteLookup() {
        val counted = CountedTracks((0 until 13_313).map { track("$it") })
        val snapshot = LibrarySnapshot(tracks = counted)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val readers = (0 until 8).map {
                pool.submit<Map<String, Track>> {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    snapshot.tracksById.also { map ->
                        assertEquals(13_313, map.size)
                        assertEquals("13312", map.getValue("13312").id)
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val maps = readers.map { it.get(10, TimeUnit.SECONDS) }
            maps.forEach { assertSame(maps.first(), it) }
            assertEquals(13_313, counted.reads.get())
        } finally {
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun copiesRefreshMetadataStatisticsAndMissingOccurrenceFallbacks() {
        val saved = track("one")
        val missing = track("missing").copy(title = "Saved unavailable")
        val initial = LibrarySnapshot(tracks = listOf(saved))
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(saved, saved, missing))
        val query = LibraryQuery(sort = TrackSort.Manual)
        PlaylistEdits.rows(playlist, initial, query)
        val changed = saved.copy(title = "Edited", durationMs = 5000, trackNumber = 7, playCount = 9)
        val next = initial.copy(tracks = listOf(changed), trackPlays = mapOf("one" to 9L, "missing" to 4L))
        val rows = PlaylistEdits.rows(playlist, next, query)
        assertEquals(playlist.entries.map { it.id }, rows.map { it.id })
        assertSame(changed, rows[0].track)
        assertSame(changed, rows[1].track)
        assertEquals(missing.copy(playCount = 4), rows[2].track)
        assertSame(saved, initial.tracksById.getValue("one"))
        val removed = next.copy(tracks = emptyList(), trackPlays = mapOf("one" to 12L))
        assertEquals(saved.copy(playCount = 12), PlaylistEdits.rows(playlist, removed, query)[0].track)
    }

    @Test fun lookupPreservesLastDuplicateAndDoesNotParticipateInSnapshotEquality() {
        val first = track("same")
        val last = first.copy(title = "Last metadata")
        val original = LibrarySnapshot(tracks = listOf(first, last))
        val copy = original.copy()
        val originalHash = original.hashCode()
        assertSame(last, original.tracksById.getValue("same"))
        assertEquals(setOf("same"), original.tracksById.keys)
        assertNull(original.tracksById["absent"])
        assertEquals(original, copy)
        assertEquals(originalHash, original.hashCode())
        assertEquals(originalHash, copy.hashCode())
        assertNotSame(original.tracksById, copy.tracksById)
        assertSame(last, copy.tracksById.getValue("same"))
        assertTrue(LibrarySnapshot().tracksById.isEmpty())
    }
}
