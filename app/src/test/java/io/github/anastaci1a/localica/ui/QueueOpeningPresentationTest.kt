package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

class QueueOpeningPresentationTest {
    private val saved = Track("synthetic:saved", "Old title", id = "duplicate", fileName = "saved.mp3", relativePath = "saved.mp3")
    private val current = saved.copy(title = "Current library title", artist = "Current artist", playCount = 17)
    private val unavailable = Track("synthetic:unavailable", "Unavailable saved title", id = "missing",
        fileName = "missing.mp3", relativePath = "missing.mp3", playCount = 99)
    private val queue = ListeningQueue(id = "opening", name = "Opening", createdAt = 0,
        entries = listOf(QueueEntry("a1", saved, "playlist-a"), QueueEntry("missing-occurrence", unavailable),
            QueueEntry("a2", saved, "playlist-b")), currentEntryId = "a2")
    private val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), key = "opening-route")
    private val library = LibrarySnapshot(tracks = listOf(current), trackPlays = mapOf("missing" to 12L))
    private val filters = LibraryFilters()
    private val style = BrowseStyle()
    private val sorts = BrowseSortSnapshot.Default
    private fun source(snapshot: LibrarySnapshot = library, queues: List<ListeningQueue> = listOf(queue)) =
        BrowseSourceRevision(snapshot, emptyList(), queues, filters)

    @Test fun physicalOpeningMatchesCanonicalQueryWithDuplicateOriginsCurrentMetadataAndUnavailableFallback() {
        val other = queue.copy(id = "unrelated", entries = queue.entries.reversed())
        val projected = requireNotNull(BrowseQueries.openingQueue(library, queue, route, "a2"))
        for (method in listOf(TrackSort.Manual, TrackSort.Title, TrackSort.MostPlayed)) for (ascending in listOf(false, true)) {
            val oracle = BrowseQueries.evaluate(library, emptyList(), route,
                LibraryQuery(sort = method, ascending = ascending), listOf(other, queue))
            assertEquals(oracle, projected)
        }
        assertEquals(listOf("a1", "missing-occurrence", "a2"), projected.rows.map { it.id })
        assertEquals(listOf("playlist-a", null, "playlist-b"), projected.rows.map { it.sourcePlaylistId })
        assertSame(current, projected.rows.first().track)
        assertSame(current, projected.rows.last().track)
        assertEquals(unavailable.copy(playCount = 12), projected.rows[1].track)
        assertEquals(3, projected.items.size)
    }

    @Test fun openingSeedSurvivesAdmissionButObsoleteSourceAndOlderOpeningCannotReplaceIt() {
        val owner = QueueOpeningPresentation()
        val oldSource = source()
        val old = requireNotNull(owner.presentation(1, "a2", oldSource, route, style, sorts, true))
        assertSame(old, owner.presentation(0, "a2", oldSource, route, style, sorts, true))
        val freshEntry = QueueEntry("new-duplicate", saved, "new-origin")
        val changed = queue.copy(entries = listOf(freshEntry) + queue.entries, currentEntryId = freshEntry.id)
        val newSource = source(queues = listOf(changed))
        val fresh = requireNotNull(owner.presentation(2, freshEntry.id, newSource, route, style, sorts, true))
        assertEquals(freshEntry.id, fresh.result.rows.first().id)
        assertEquals("new-origin", fresh.result.rows.first().sourcePlaylistId)
        assertFalse(matchesBrowseRequest(old, newSource, route, style, sorts))
        assertTrue(matchesBrowseRequest(fresh, newSource, route, style, sorts))
        assertSame(fresh, owner.presentation(0, freshEntry.id, newSource, route, style, sorts, true))
        assertNull(owner.presentation(1, "a2", oldSource, route, style, sorts, true))
        val checkpointSource = source(queues = listOf(changed.copy(positionMs = 1234)))
        assertNull("Ordinary checkpoints do not broaden the synchronous opening path",
            owner.presentation(0, freshEntry.id, checkpointSource, route, style, sorts, true))
    }

    @Test fun exactWarmPresentationIsReusedWhileStaleLibraryMetadataRequiresCanonicalProjection() {
        val owner = QueueOpeningPresentation()
        val stamp = source()
        val warm = PresentedBrowse(route, style, requireNotNull(BrowseQueries.openingQueue(library, queue, route, "a2")), stamp, sorts)
        assertSame(warm, owner.presentation(1, "a2", stamp, route, style, sorts, true, warm))
        val changedLibrary = library.copy(tracks = listOf(current.copy(title = "New metadata")))
        val changedSource = source(snapshot = changedLibrary)
        val updated = requireNotNull(owner.presentation(2, "a2", changedSource, route, style, sorts, true, warm))
        assertNotSame(warm, updated)
        assertEquals("New metadata", updated.result.rows.last().track.title)
        assertSame(changedSource, updated.source)
    }

    @Test fun missingOccurrenceUnreadySourcesAndNoncanonicalScopesNeverSeedASubstitute() {
        val stamp = source()
        assertNull(QueueOpeningPresentation().presentation(1, "a2", stamp, route, style, sorts, false))
        assertNull(QueueOpeningPresentation().presentation(1, null, stamp, route, style, sorts, true))
        assertNull(BrowseQueries.openingQueue(library, queue, route, saved.id))
        assertNull(BrowseQueries.openingQueue(library, queue, route, "absent"))
        for (excluded in listOf(route.copy(search = true), route.copy(globalSearch = true), route.copy(text = "title"),
            route.copy(filter = MediaKind.Album), route.copy(collection = null),
            route.copy(collection = MediaRef(MediaKind.Queue, "other")))) {
            assertNull(BrowseQueries.openingQueue(library, queue, excluded, "a2"))
            assertNull(QueueOpeningPresentation().presentation(1, "a2", stamp, excluded, style, sorts, true))
        }
        assertNull(QueueOpeningPresentation().presentation(0, "a2", stamp, route, style, sorts, true))
    }
}
