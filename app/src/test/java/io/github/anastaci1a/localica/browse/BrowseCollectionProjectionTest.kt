package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

class BrowseCollectionProjectionTest {
    private val saved = Track("content://one", "Saved", fileName = "one.mp3", relativePath = "one.mp3")
    private val missing = saved.copy(id = "missing", title = "Unavailable")
    private val current = saved.copy(title = "Current", playCount = 7)
    private fun snapshot() = LibrarySnapshot(tracks = listOf(current), trackPlays = mapOf("missing" to 4L))

    private class Counted<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }

    @Test fun playlistDetailDoesNotReadUnrelatedOccurrencesAndRetainsCurrentFallbackMetadata() {
        val unrelated = Counted(List(13_313) { SongRow("other-$it", saved) })
        val other = ManualPlaylist(id = "other", name = "Other", entries = unrelated)
        val chosen = ManualPlaylist(id = "chosen", name = "Chosen", entries = listOf(
            SongRow("first", saved), SongRow("missing", missing), SongRow("repeat", saved)))
        unrelated.reads = 0 // Constructor captures its saved order; only query work is measured.
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, chosen.id))
        val result = BrowseQueries.evaluate(snapshot(), listOf(other, chosen), route,
            LibraryQuery(sort = TrackSort.Title, ascending = false))
        assertEquals(listOf("first", "missing", "repeat"), result.rows.map { it.id })
        assertEquals(listOf(current, missing.copy(playCount = 4), current), result.rows.map { it.track })
        assertTrue(result.rows.all { it.sourcePlaylistId == chosen.id })
        assertEquals(0, unrelated.reads)
    }

    @Test fun queueDetailSearchPreservesOccurrenceOrderAndAvoidsOtherQueues() {
        val unrelated = Counted(List(13_313) { QueueEntry("other-$it", saved) })
        val other = ListeningQueue(id = "other", name = "Other", entries = unrelated)
        val chosen = ListeningQueue(id = "chosen", name = "Chosen", entries = listOf(
            QueueEntry("first", saved, "source-a"), QueueEntry("missing", missing),
            QueueEntry("repeat", saved, "source-b")))
        unrelated.reads = 0
        val library = snapshot()
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, chosen.id), search = true)
        val result = BrowseQueries.evaluate(library, emptyList(), route, queues = listOf(other, chosen))
        assertEquals(listOf("first", "missing", "repeat"), result.rows.map { it.id })
        assertEquals(listOf(current, missing.copy(playCount = 4), current), result.rows.map { it.track })
        assertEquals(listOf("source-a", null, "source-b"), result.rows.map { it.sourcePlaylistId })
        val filtered = BrowseQueries.evaluate(library, emptyList(), route.copy(text = "Current"),
            queues = listOf(other, chosen))
        assertEquals(listOf("first", "repeat"), filtered.rows.map { it.id })
        assertEquals(0, unrelated.reads)
    }

    @Test fun missingCollectionsStayEmptyAndDuplicateCollectionIdsSelectTheFirst() {
        val first = ManualPlaylist(id = "same", name = "First", entries = listOf(SongRow("first", saved)))
        val second = first.copy(entries = listOf(SongRow("second", missing)))
        val queue = ListeningQueue(id = "same", name = "First", entries = listOf(QueueEntry("first", saved)))
        val otherQueue = queue.copy(entries = listOf(QueueEntry("second", missing)))
        val library = snapshot()
        for (kind in listOf(MediaKind.Playlist, MediaKind.Queue)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "same"))
            assertEquals(listOf("first"), BrowseQueries.evaluate(library, listOf(first, second), route,
                queues = listOf(queue, otherQueue)).rows.map { it.id })
            assertTrue(BrowseQueries.evaluate(library, listOf(first, second),
                route.copy(collection = MediaRef(kind, "absent")), queues = listOf(queue, otherQueue)).rows.isEmpty())
        }
    }
}
