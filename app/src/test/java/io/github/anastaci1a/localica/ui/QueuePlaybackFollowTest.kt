package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QueuePlaybackFollowTest {
    private val owner = Any()
    private fun QueuePlaybackFollowCoordinator.observe(target: String? = "first", eligible: Boolean = true,
        owner: Any = this@QueuePlaybackFollowTest.owner, generation: Long = 0) = observe(target, owner, eligible, generation)

    @Test fun initiallyAttachedQueueRequestsItsExactCurrentOccurrence() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        assertTrue(state.attached)
        assertEquals("first", state.request?.occurrenceId)
    }

    @Test fun touchExplorationStartsDetachedAndExplicitReturnAttaches() {
        val state = QueuePlaybackFollowCoordinator(initiallyAttached = false)
        state.observe()
        assertNull(state.request)
        assertFalse(state.attached)
        assertTrue(state.returnToCurrent("first", owner, true, 0))
        assertTrue(state.attached)
        assertEquals("first", state.request?.occurrenceId)
    }

    @Test fun initialHiddenRouteWaitsForFirstAdmission() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe(eligible = false)
        state.observe("second", eligible = false)
        assertNull(state.request)
        state.observe("second")
        assertEquals("second", state.request?.occurrenceId)
    }

    @Test fun playbackCheckpointsAndRowPublicationsDoNotRestartTheSameOccurrence() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        val first = state.request!!
        repeat(10) { state.observe(); assertSame(first, state.request) }
        state.finish(first)
        repeat(10) { state.observe(); assertNull(state.request) }
    }

    @Test fun newerOccurrenceCancelsTheOldJobAndOwnsTheOnlyRequest() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        val first = state.request!!
        val job = Job()
        assertTrue(state.bind(first, job))
        assertTrue(state.beginMotion(first))
        state.observe("second")
        assertTrue(job.isCancelled)
        assertFalse(state.moving)
        assertEquals("second", state.request?.occurrenceId)
        val second = state.request
        state.finish(first)
        assertSame(second, state.request)
        assertFalse(state.beginMotion(first))
        assertFalse(state.bind(first, Job()))
    }

    @Test fun scrollingDetachesAndDoesNotFollowLaterPlaybackUntilReturn() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.userScroll()
        state.observe("second")
        assertFalse(state.attached)
        assertNull(state.request)
        assertTrue(state.returnToCurrent("second", owner, true, 0))
        val returned = state.request!!
        state.finish(returned)
        state.observe("third")
        assertTrue(state.attached)
        assertEquals("third", state.request?.occurrenceId)
    }

    @Test fun stationaryPointerAtRestKeepsAttachmentButCancelsPendingOrRunningNavigation() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.finish(state.request!!)
        state.pointerDown()
        assertTrue(state.attached)
        state.observe("second")
        state.pointerDown()
        assertFalse(state.attached)
        assertNull(state.request)
        state.returnToCurrent("second", owner, true, 0)
        state.beginMotion(state.request!!)
        state.pointerDown()
        assertFalse(state.attached)
        assertFalse(state.moving)
        assertNull(state.request)
    }

    @Test fun hiddenSelectionSearchPinchAndModalTakeoversRetirePendingOrRunningRequests() {
        for (moving in listOf(false, true)) {
            val state = QueuePlaybackFollowCoordinator()
            state.observe()
            val job = Job()
            state.bind(state.request!!, job)
            if (moving) state.beginMotion(state.request!!)
            state.observe(eligible = false)
            assertFalse(state.attached)
            assertNull(state.request)
            assertTrue(job.isCancelled)
            state.observe()
            assertNull(state.request)
            assertTrue(state.returnToCurrent("first", owner, true, 1))
            assertEquals(1L, state.request?.modalGeneration)
        }
    }

    @Test fun blockedOccurrenceChangesAreConsumedWithoutStaleReplayWhenOwnershipReturns() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.finish(state.request!!)
        state.observe("second", eligible = false)
        state.observe("second")
        assertTrue(state.attached)
        assertNull(state.request)
        state.observe("third")
        assertEquals("third", state.request?.occurrenceId)
    }

    @Test fun restingHeldContactConsumesTransportWithoutDetachingOrReplayingAtRelease() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.finish(state.request!!)
        state.pointerDown()
        state.observe("second", eligible = false)
        state.observe("third", eligible = false)
        assertTrue(state.attached)
        assertNull(state.request)
        state.observe("third")
        assertNull(state.request)
        state.observe("fourth")
        assertEquals("fourth", state.request?.occurrenceId)
    }

    @Test fun scopedSearchAllowsExplicitReturnWithoutAutomaticInitialOrCursorFollowing() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe("first", owner, true, 0, automatic = false)
        assertNull(state.request)
        assertTrue(state.returnToCurrent("first", owner, true, 0))
        val request = state.request!!
        state.observe("first", owner, true, 0, automatic = false)
        assertSame(request, state.request)
        state.finish(request)
        state.observe("second", owner, true, 0, automatic = false)
        assertNull(state.request)
    }

    @Test fun structureChangesCancelButNeverCreateAFollowByThemselves() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.finish(state.request!!)
        state.observe(owner = Any())
        assertTrue(state.attached)
        assertNull(state.request)
    }

    @Test fun ownerReplacementDuringNavigationDetachesForExplicitRecovery() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.observe(owner = Any())
        assertFalse(state.attached)
        assertNull(state.request)
    }

    @Test fun orderActionCancellationDoesNotChangeAttachmentOrReplayTheOccurrence() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.cancel()
        state.observe()
        assertTrue(state.attached)
        assertNull(state.request)
        state.observe("second")
        assertEquals("second", state.request?.occurrenceId)
    }

    @Test fun invalidReturnDoesNotAttachOrReplaceAnAdmittedRequest() {
        val state = QueuePlaybackFollowCoordinator(false)
        assertFalse(state.returnToCurrent(null, owner, true, 0))
        assertFalse(state.returnToCurrent("first", owner, false, 0))
        assertFalse(state.attached)
        assertNull(state.request)
    }

    @Test fun losingActiveOccurrenceCancelsMotionAndNeverTargetsAnInactiveQueue() {
        val state = QueuePlaybackFollowCoordinator()
        state.observe()
        state.observe(target = null, eligible = false)
        assertNull(state.request)
        assertFalse(state.attached)
    }

    @Test fun exactDuplicateOccurrenceResolvesAgainstPublishedRowsAndMissingNeverFallsBack() {
        val track = Track("song", "Repeated song", fileName = "song.mp3", relativePath = "song.mp3")
        fun item(id: String) = SongRow(id, track).let { row ->
            BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row = row)
        }
        val first = item("first")
        val duplicate = item("duplicate")
        val request = QueuePlaybackFollowRequest("duplicate", owner, 0)
        assertEquals(2, request.targetIndex(listOf(first, duplicate)))
        assertEquals(1, request.targetIndex(listOf(duplicate, first)))
        assertNull(request.targetIndex(listOf(first)))
        assertNull(request.targetIndex(emptyList()))
        assertNull(QueuePlaybackFollowRequest("song", owner, 0).targetIndex(listOf(first, duplicate)))
    }

    @Test fun internalScrollOwnershipIsNestedAndReleasedAfterFailure() = runTest {
        val ownership = BrowseScrollOwnership()
        assertFalse(ownership.internal)
        try {
            ownership.during {
                assertTrue(ownership.internal)
                ownership.during { assertEquals(2, ownership.depth) }
                assertEquals(1, ownership.depth)
                error("cancel internal restoration")
            }
        } catch (_: IllegalStateException) { }
        assertFalse(ownership.internal)
        assertEquals(0, ownership.depth)
    }
}
