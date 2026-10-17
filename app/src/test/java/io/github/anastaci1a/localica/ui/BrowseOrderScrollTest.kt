package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseResult
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEntry
import org.junit.Assert.*
import org.junit.Test

class BrowseOrderScrollTest {
    private val track = Track("song", "Repeated song", fileName = "song.mp3", relativePath = "song.mp3")
    private val rows = listOf(SongRow("first-occurrence", track), SongRow("second-occurrence", track))
    private val playlist = ManualPlaylist(id = "playlist", name = "Mix", entries = rows)
    private val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), key = "route")
    private val owner = Any()
    private val committed get() = BrowseOrderGeneration(route.collection!!, playlist.entries, playlist.order)

    private fun source(playlists: List<ManualPlaylist> = listOf(playlist), queues: List<ListeningQueue> = emptyList()) =
        BrowseSourceRevision(LibrarySnapshot(), playlists, queues, LibraryFilters())

    private fun presentation(source: BrowseSourceRevision = source(), route: BrowseRoute = this.route,
        rows: List<SongRow> = this.rows) = PresentedBrowse(route, BrowseStyle(), BrowseResult(rows.map {
        BrowserItem(MediaRef(MediaKind.Song, it.track.id), it.track.title, null, listOf(it), row = it)
    }), source)

    private fun complete(coordinator: BrowseOrderScrollCoordinator, token: BrowseOrderScrollToken,
        changed: Boolean = true, committed: BrowseOrderGeneration? = this.committed,
        currentOwner: Any = owner, currentRoute: BrowseRoute = route, target: String? = null) =
        coordinator.complete(token, currentOwner, currentRoute, changed, committed, target)

    @Test fun beginningANewActionCancelsAnExistingWaitOrMotionBeforeItsResult() {
        val coordinator = BrowseOrderScrollCoordinator()
        val firstToken = coordinator.begin(owner, route)
        assertTrue(complete(coordinator, firstToken))
        val firstRequest = coordinator.request!!
        val revision = coordinator.revision
        val secondToken = coordinator.begin(owner, route)
        assertTrue(coordinator.revision > revision)
        assertNull(coordinator.request)
        assertFalse(coordinator.isCurrent(firstRequest, owner, route))
        assertFalse(complete(coordinator, firstToken))
        assertNull(coordinator.request)
        assertTrue(complete(coordinator, secondToken))
        assertNotSame(firstRequest, coordinator.request)
    }

    @Test fun newerActionWinsWhenCommandCallbacksArriveOutOfOrder() {
        val coordinator = BrowseOrderScrollCoordinator()
        val old = coordinator.begin(owner, route)
        val latest = coordinator.begin(owner, route)
        assertTrue(complete(coordinator, latest, target = rows.last().id))
        val request = coordinator.request
        assertFalse(complete(coordinator, old, target = rows.first().id))
        assertSame(request, coordinator.request)
        assertEquals(rows.last().id, coordinator.request?.targetOccurrenceId)
    }

    @Test fun failedAndUnchangedCompletionsLeaveNoRequestAndConsumeTheirToken() {
        for ((changed, generation) in listOf(false to committed, true to null)) {
            val coordinator = BrowseOrderScrollCoordinator()
            assertTrue(complete(coordinator, coordinator.begin(owner, route)))
            val token = coordinator.begin(owner, route)
            assertFalse(complete(coordinator, token, changed, generation))
            assertNull(coordinator.request)
            assertFalse(complete(coordinator, token))
        }
    }

    @Test fun touchRouteHideOrPopupCancellationInvalidatesPendingAndCompletedActions() {
        val coordinator = BrowseOrderScrollCoordinator()
        val waiting = coordinator.begin(owner, route)
        val revision = coordinator.revision
        coordinator.cancel()
        assertTrue(coordinator.revision > revision)
        assertFalse(complete(coordinator, waiting))
        assertNull(coordinator.request)
        assertTrue(complete(coordinator, coordinator.begin(owner, route)))
        val moving = coordinator.request!!
        coordinator.cancel()
        assertNull(coordinator.request)
        assertFalse(coordinator.isCurrent(moving, owner, route))
    }

    @Test fun duplicateCompletionAndOldMotionFinishCannotReplaceOrCancelNewerWork() {
        val coordinator = BrowseOrderScrollCoordinator()
        val token = coordinator.begin(owner, route)
        assertTrue(complete(coordinator, token))
        val old = coordinator.request!!
        assertFalse(complete(coordinator, token, target = rows.last().id))
        assertSame(old, coordinator.request)
        val next = coordinator.begin(owner, route)
        coordinator.finish(old)
        assertTrue(complete(coordinator, next))
        val current = coordinator.request!!
        coordinator.finish(old)
        assertSame(current, coordinator.request)
        coordinator.finish(current)
        assertNull(coordinator.request)
    }

    @Test fun ownerUsesIdentityAndCompletionRequiresTheExactRoute() {
        data class Owner(val name: String)
        val initialOwner = Owner("popup")
        val coordinator = BrowseOrderScrollCoordinator()
        val token = coordinator.begin(initialOwner, route)
        assertFalse(complete(coordinator, token, currentOwner = Owner("popup")))
        assertFalse(complete(coordinator, token, currentOwner = initialOwner))
        for (otherRoute in listOf(route.copy(key = "new-visit"), route.copy(text = "new query"))) {
            val next = coordinator.begin(owner, route)
            assertFalse(complete(coordinator, next, currentRoute = otherRoute))
            assertNull(coordinator.request)
        }
    }

    @Test fun successfulReceiptForAnotherCollectionOrGlobalSearchCannotRequestScrolling() {
        val coordinator = BrowseOrderScrollCoordinator()
        val wrongCollection = BrowseOrderGeneration(MediaRef(MediaKind.Playlist, "other"), playlist.entries, playlist.order)
        assertFalse(complete(coordinator, coordinator.begin(owner, route), committed = wrongCollection))
        val global = route.copy(search = true, globalSearch = true)
        assertFalse(complete(coordinator, coordinator.begin(owner, global), currentRoute = global))
        assertNull(coordinator.request)
    }

    @Test fun targetUsesExactOccurrenceIdentityAndIncludesTheHeader() {
        val coordinator = BrowseOrderScrollCoordinator()
        assertTrue(complete(coordinator, coordinator.begin(owner, route), target = rows.last().id))
        val request = coordinator.request!!
        assertEquals(2, request.targetIndex(presentation(), owner, route))
        assertEquals(1, request.targetIndex(presentation(rows = rows.reversed()), owner, route))
        assertEquals(0, request.targetIndex(presentation(rows = rows.take(1)), owner, route))
        assertNull(request.targetIndex(null, owner, route))
        assertNull(request.targetIndex(presentation(), Any(), route))
        assertNull(request.targetIndex(presentation(), owner, route.copy(key = "new-visit")))
        assertNull(request.targetIndex(presentation(route = route.copy(text = "old")), owner, route))
    }

    @Test fun followCurrentResolvesAReadinessTimeOccurrenceWithoutMutatingTheReceiptTarget() {
        val coordinator = BrowseOrderScrollCoordinator()
        val token = coordinator.begin(owner, route)
        assertTrue(coordinator.complete(token, owner, route, true, committed, rows.first().id, followCurrent = true))
        val request = coordinator.request!!
        assertTrue(request.followCurrent)
        assertEquals(2, request.targetIndex(presentation(), owner, route, rows.last().id))
        assertEquals(0, request.targetIndex(presentation(), owner, route, null))
        assertEquals(rows.first().id, request.targetOccurrenceId)
    }

    @Test fun nullMissingAndTrackIdTargetsAllResolveToTop() {
        val coordinator = BrowseOrderScrollCoordinator()
        for (target in listOf(null, "removed-occurrence", track.id)) {
            assertTrue(complete(coordinator, coordinator.begin(owner, route), target = target))
            assertEquals(0, coordinator.request!!.targetIndex(presentation(), owner, route))
        }
    }

    @Test fun presentationMustContainTheCommittedEntriesIdentityAndSavedOrder() {
        val coordinator = BrowseOrderScrollCoordinator()
        assertTrue(complete(coordinator, coordinator.begin(owner, route)))
        val request = coordinator.request!!
        val equalEntries = playlist.copy(entries = ArrayList(playlist.entries))
        assertEquals(playlist, equalEntries)
        assertNull(request.targetIndex(presentation(source(listOf(equalEntries))), owner, route))
        val otherOrder = playlist.copy(order = playlist.order.copy(sort = TrackSort.Title))
        assertNull(request.targetIndex(presentation(source(listOf(otherOrder))), owner, route))
        assertEquals(0, request.targetIndex(presentation(source(listOf(playlist.copy(order = playlist.order.copy())))), owner, route))
        assertNull(request.targetIndex(presentation(source(emptyList())), owner, route))
    }

    @Test fun queueCursorAndOuterSourceCopiesDoNotChangeTheCommittedGeneration() {
        val entries = rows.map { QueueEntry(it.id, it.track, sourcePlaylistId = playlist.id) }
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = entries)
        val queueRoute = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), key = "queue-route")
        val generation = BrowseOrderGeneration(queueRoute.collection!!, entries, queue.order)
        val coordinator = BrowseOrderScrollCoordinator()
        assertTrue(complete(coordinator, coordinator.begin(owner, queueRoute), committed = generation,
            currentRoute = queueRoute, target = rows.last().id))
        val cursorCopy = queue.copy(currentEntryId = rows.last().id, positionMs = 123L)
        val currentSource = source(playlists = listOf(playlist.copy()), queues = arrayListOf(cursorCopy))
        assertTrue(generation.matches(currentSource))
        assertEquals(2, coordinator.request!!.targetIndex(presentation(currentSource, queueRoute), owner, queueRoute))
        assertFalse(generation.matches(source(queues = listOf(cursorCopy.copy(entries = ArrayList(entries))))))
        val otherOrder = cursorCopy.copy(order = SavedOrder(entries.map { it.id }.reversed()))
        assertFalse(generation.matches(source(queues = listOf(otherOrder))))
    }

    @Test fun sourceMatchingNeverInfersCollectionIdentityFromSongOrPlaylistOrigin() {
        val entries = rows.map { QueueEntry(it.id, it.track, sourcePlaylistId = playlist.id) }
        val queue = ListeningQueue(id = playlist.id, name = "Same ID, different kind", entries = entries)
        assertFalse(committed.matches(source(playlists = emptyList(), queues = listOf(queue))))
        val wrongPlaylist = playlist.copy(id = "different-playlist")
        assertFalse(committed.matches(source(playlists = listOf(wrongPlaylist))))
        val queueGeneration = BrowseOrderGeneration(MediaRef(MediaKind.Queue, queue.id), entries, queue.order)
        assertFalse(queueGeneration.matches(source()))
    }
}
