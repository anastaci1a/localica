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

    @Test fun initialPlacementConsumesAdmissionWithoutChangingLaterTransitionEasing() {
        val state = QueuePlaybackFollowCoordinator()
        assertEquals("first", state.initialTarget("first", owner, true, 0))
        state.positionedInitially("first", owner, 0)
        state.observe()
        assertNull(state.request)
        assertNull(state.initialTarget("first", owner, true, 0))
        state.observe("second")
        assertFalse(state.request!!.initialPosition)
        assertEquals("second", state.request!!.occurrenceId)
    }

    @Test fun freshDetachedOpenPositionsCurrentButRecreationDoesNotReplayOrReattach() {
        val state = QueuePlaybackFollowCoordinator(false, openingRequest = 1)
        assertEquals("second", state.initialTarget("second", owner, true, 2))
        state.positionedInitially("second", owner, 2)
        assertFalse(state.attached)
        assertEquals(2L, state.consumedOpeningRequest)
        state.observe("third")
        assertNull(state.request)
        val restored = QueuePlaybackFollowCoordinator(state.attached, state.consumedOpeningRequest)
        assertNull(restored.initialTarget("third", owner, true, 2))
        restored.observe("third")
        assertNull(restored.request)
        assertFalse(restored.attached)
        assertEquals("third", restored.initialTarget("third", owner, true, 3))
    }

    @Test fun coldOpeningWaitsForExactRowsWithoutAdmittingMotionAndUserTakeoverConsumesIt() {
        val state = QueuePlaybackFollowCoordinator(false)
        state.observe("first", owner, false, 0, openingRequest = 1, initialEligible = true)
        val pending = state.request!!
        assertTrue(pending.initialPosition)
        repeat(3) {
            state.observe("first", owner, false, 0, openingRequest = 1, initialEligible = true)
            assertSame(pending, state.request)
        }
        assertEquals("first", state.initialTarget("first", owner, true, 1))
        assertEquals("latest", state.initialTarget("latest", owner, true, 1))
        assertEquals("latest", state.initialTarget("latest", Any(), true, 1))
        state.userScroll()
        assertNull(state.initialTarget("first", owner, true, 1))
        state.observe("first", owner, true, 0, openingRequest = 1)
        assertNull(state.request)
        assertFalse(state.attached)
    }

    @Test fun attachedRecreationKeepsViewportWithoutAnOpenButFutureTransitionsStillFollow() {
        val state = QueuePlaybackFollowCoordinator(true, openingRequest = 4, restored = true)
        assertNull(state.initialTarget("restored-current", owner, true, 4))
        state.observe("restored-current", owner, true, 0, openingRequest = 4)
        assertNull(state.request)
        assertTrue(state.attached)
        state.observe("next-current", owner, true, 0, openingRequest = 4)
        assertEquals("next-current", state.request?.occurrenceId)
        assertFalse(state.request!!.initialPosition)
        assertEquals("next-current", state.initialTarget("next-current", owner, true, 5))
    }

    @Test fun savedAdmissionRestoresLegacyAttachmentAndCurrentEpochWithoutReplayingAnOpening() {
        val legacy = requireNotNull(QueuePlaybackFollowCoordinator.restoreState(false))
        assertFalse(legacy.attached)
        assertNull(legacy.initialTarget("current", owner, true, 0))
        val current = requireNotNull(QueuePlaybackFollowCoordinator.restoreState(arrayListOf(true, 7L)))
        assertTrue(current.attached)
        assertEquals(7L, current.consumedOpeningRequest)
        assertNull(current.initialTarget("current", owner, true, 7))
        assertEquals("current", current.initialTarget("current", owner, true, 8))
    }

    @Test fun initialPlacementRequiresOwnershipAndSearchNeverAdmitsOrReplaysAnAutomaticOpening() {
        val state = QueuePlaybackFollowCoordinator()
        assertNull(state.initialTarget("first", owner, false, 1))
        assertNull(state.initialTarget(null, owner, true, 1))
        state.observe("first", owner, true, 0, automatic = false, openingRequest = 1)
        assertNull(state.request)
        assertEquals(1L, state.consumedOpeningRequest)
        assertNull(state.initialTarget("first", owner, true, 1))
        assertTrue(state.returnToCurrent("first", owner, true, 0))
        assertFalse(state.request!!.initialPosition)
    }

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
