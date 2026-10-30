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

    @Test fun globalSongSearchIgnoresNonNullPlaylistAndQueueOrigins() {
        val outside = saved.copy(id = "outside", title = "Outside")
        val playlist = ManualPlaylist(id = "chosen", name = "Chosen", entries = listOf(
            SongRow("first", saved), SongRow("missing", missing), SongRow("repeat", saved)))
        val queue = ListeningQueue(id = "chosen", name = "Chosen", entries = listOf(
            QueueEntry("first", saved), QueueEntry("missing", missing), QueueEntry("repeat", saved)))
        val playlists = listOf(playlist)
        val queues = listOf(queue)
        for ((tab, kind) in listOf(MainTab.Playlists to MediaKind.Playlist, MainTab.Queues to MediaKind.Queue)) {
            val library = snapshot().copy(tracks = listOf(current, outside))
            val origin = BrowseRoute(tab, MediaRef(kind, "chosen"), search = true)
            assertTrue(BrowseQueries.evaluate(library, playlists, origin.copy(text = "Outside"), queues = queues).rows.isEmpty())
            assertEquals(listOf("first", "repeat"), BrowseQueries.evaluate(library, playlists,
                origin.copy(text = "Current"), queues = queues).rows.map { it.id })

            // Prepare from the non-null origin first: a prewarmed global result must not mask this path.
            val global = origin.copy(globalSearch = true, filter = MediaKind.Song)
            val result = BrowseQueries.evaluate(library, playlists, global, queues = queues)
            assertEquals(listOf(SongRow(current.id, current), SongRow(outside.id, outside)), result.rows)
            assertEquals(SongListView.Search, result.scope)
            assertSame(result, BrowseQueries.evaluate(library, playlists,
                global.copy(tab = MainTab.Library, collection = null), queues = queues))
            assertEquals(listOf(SongRow(outside.id, outside)), BrowseQueries.evaluate(library, playlists,
                global.copy(text = "Outside"), queues = queues).rows)
            assertEquals(listOf(SongRow(current.id, current)), BrowseQueries.evaluate(library, playlists,
                global.copy(text = "Current"), queues = queues).rows)
            assertTrue(BrowseQueries.evaluate(library, playlists, global.copy(text = "Unavailable"),
                queues = queues).rows.isEmpty())
        }
    }

    @Test fun selectedPlaylistReplacementReorderAndRemovalInvalidateWarmCatalog() {
        val library = snapshot()
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "chosen"), search = true)
        val original = ManualPlaylist(id = "chosen", name = "Chosen", entries = listOf(
            SongRow("first", saved), SongRow("missing", missing), SongRow("repeat", saved)))
        var playlists = listOf(original)
        fun evaluate(text: String = "") = BrowseQueries.evaluate(library, playlists, route.copy(text = text),
            LibraryQuery(sort = TrackSort.Title, ascending = false))
        fun assertWarmRows(expected: List<SongRow>) {
            val result = evaluate()
            assertEquals(expected, result.rows)
            assertSame(result, evaluate())
        }
        val first = SongRow("first", current, original.id)
        val repeat = SongRow("repeat", current, original.id)
        val unavailable = SongRow("missing", missing.copy(playCount = 4), original.id)
        assertWarmRows(listOf(first, unavailable, repeat))

        // Same library snapshot, collection ID and occurrence IDs; only saved fallback metadata changes.
        val replacement = original.copy(entries = listOf(original.entries[0],
            original.entries[1].copy(track = missing.copy(title = "Replacement")), original.entries[2]))
        playlists = listOf(replacement)
        val replaced = unavailable.copy(track = missing.copy(title = "Replacement", playCount = 4))
        assertWarmRows(listOf(first, replaced, repeat))
        assertTrue(evaluate("Unavailable").rows.isEmpty())
        assertEquals(listOf(replaced), evaluate("Replacement").rows)

        val reordered = replacement.copy(entries = listOf(replacement.entries[2], replacement.entries[0], replacement.entries[1]))
        playlists = listOf(reordered)
        assertWarmRows(listOf(repeat, first, replaced))
        playlists = listOf(reordered.copy(entries = reordered.entries.filterNot { it.id == "first" }))
        assertWarmRows(listOf(repeat, replaced))
        assertEquals(listOf(repeat), evaluate("Current").rows)

        playlists = emptyList()
        assertWarmRows(emptyList())
        assertTrue(evaluate("Replacement").rows.isEmpty())
    }

    @Test fun selectedQueueReplacementReorderAndRemovalInvalidateWarmCatalog() {
        val library = snapshot()
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "chosen"), search = true)
        val original = ListeningQueue(id = "chosen", name = "Chosen", entries = listOf(
            QueueEntry("first", saved, "source-a"), QueueEntry("missing", missing),
            QueueEntry("repeat", saved, "source-b")))
        var queues = listOf(original)
        fun evaluate(text: String = "") = BrowseQueries.evaluate(library, emptyList(), route.copy(text = text),
            LibraryQuery(sort = TrackSort.Title, ascending = false), queues = queues)
        fun assertWarmRows(expected: List<SongRow>) {
            val result = evaluate()
            assertEquals(expected, result.rows)
            assertSame(result, evaluate())
        }
        val first = SongRow("first", current, "source-a")
        val repeat = SongRow("repeat", current, "source-b")
        val unavailable = SongRow("missing", missing.copy(playCount = 4))
        assertWarmRows(listOf(first, unavailable, repeat))

        val replacement = original.copy(entries = listOf(original.entries[0],
            original.entries[1].copy(track = missing.copy(title = "Replacement"), sourcePlaylistId = "replacement-source"),
            original.entries[2]))
        queues = listOf(replacement)
        val replaced = unavailable.copy(track = missing.copy(title = "Replacement", playCount = 4),
            sourcePlaylistId = "replacement-source")
        assertWarmRows(listOf(first, replaced, repeat))
        assertTrue(evaluate("Unavailable").rows.isEmpty())
        assertEquals(listOf(replaced), evaluate("Replacement").rows)

        val reordered = replacement.copy(entries = listOf(replacement.entries[2], replacement.entries[0], replacement.entries[1]))
        queues = listOf(reordered)
        assertWarmRows(listOf(repeat, first, replaced))
        queues = listOf(reordered.copy(entries = reordered.entries.filterNot { it.id == "first" }))
        assertWarmRows(listOf(repeat, replaced))
        assertEquals(listOf(repeat), evaluate("Current").rows)

        queues = emptyList()
        assertWarmRows(emptyList())
        assertTrue(evaluate("Replacement").rows.isEmpty())
    }

    @Test fun canceledPlaylistDetailRebuildsCompleteCatalogOnRetry() {
        val entries = Counted(List(257) { SongRow("occ-$it", if (it % 2 == 0) saved else missing) })
        val playlist = ManualPlaylist(id = "chosen", name = "Chosen", entries = entries)
        val playlists = listOf(playlist)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), search = true)
        val expected = List(257) { SongRow("occ-$it",
            if (it % 2 == 0) current else missing.copy(playCount = 4), playlist.id) }
        assertCanceledDetailRebuilds(entries, expected) { library, text, checkActive ->
            BrowseQueries.evaluate(library, playlists, route.copy(text = text), checkActive = checkActive)
        }
    }

    @Test fun canceledQueueDetailRebuildsCompleteCatalogOnRetry() {
        val entries = Counted(List(257) { QueueEntry("occ-$it",
            if (it % 2 == 0) saved else missing, "source-${it % 3}") })
        val queue = ListeningQueue(id = "chosen", name = "Chosen", entries = entries)
        val queues = listOf(queue)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), search = true)
        val expected = List(257) { SongRow("occ-$it",
            if (it % 2 == 0) current else missing.copy(playCount = 4), "source-${it % 3}") }
        assertCanceledDetailRebuilds(entries, expected) { library, text, checkActive ->
            BrowseQueries.evaluate(library, emptyList(), route.copy(text = text), queues = queues, checkActive = checkActive)
        }
    }

    private fun assertCanceledDetailRebuilds(entries: Counted<*>, expected: List<SongRow>,
        evaluate: (LibrarySnapshot, String, () -> Unit) -> BrowseResult) {
        // Enumerate this cold, unfiltered preparation's checkpoints, including the final pre-cache check.
        // Each canceled attempt uses a fresh snapshot; its retry then uses that exact same snapshot.
        var checkpoints = 0
        assertEquals(expected, evaluate(snapshot(), "") { checkpoints++ }.rows)
        assertTrue("The fixture must reach checks after entry", checkpoints > 1)
        for (cancelAt in 1..checkpoints) {
            val library = snapshot()
            val canceled = java.util.concurrent.CancellationException("checkpoint $cancelAt")
            var checks = 0
            entries.reads = 0
            try {
                evaluate(library, "") { if (++checks == cancelAt) throw canceled }
                fail("Expected cancellation at checkpoint $cancelAt")
            } catch (actual: java.util.concurrent.CancellationException) {
                assertSame(canceled, actual)
            }
            assertEquals(cancelAt, checks)
            entries.reads = 0
            val retry = evaluate(library, "") {}
            assertEquals("Complete retry after checkpoint $cancelAt", expected, retry.rows)
            assertTrue("Canceled preparation must not supply the retry catalog", entries.reads > 0)
            entries.reads = 0
            assertSame(retry, evaluate(library, "") {})
            assertEquals(0, entries.reads)
            assertEquals(expected.filter { it.track.id == missing.id }, evaluate(library, "Unavailable") {}.rows)
            assertEquals(0, entries.reads)
        }
    }

    @Test fun emptyFirstDuplicateCollectionDoesNotFallThroughToNonemptyDuplicate() {
        val first = ManualPlaylist(id = "same", name = "First", entries = emptyList())
        val second = first.copy(entries = listOf(SongRow("second", saved)))
        val queue = ListeningQueue(id = "same", name = "First", entries = emptyList())
        val otherQueue = queue.copy(entries = listOf(QueueEntry("second", saved)))
        val library = snapshot()
        for (kind in listOf(MediaKind.Playlist, MediaKind.Queue)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "same"))
            val result = BrowseQueries.evaluate(library, listOf(first, second), route, queues = listOf(queue, otherQueue))
            assertTrue(result.rows.isEmpty())
            assertEquals(listOf("second"), BrowseQueries.evaluate(library, listOf(second, first), route,
                queues = listOf(otherQueue, queue)).rows.map { it.id })
        }
    }
}
