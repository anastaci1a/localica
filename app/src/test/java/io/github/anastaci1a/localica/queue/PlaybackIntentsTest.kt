package io.github.anastaci1a.localica.queue

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PlaybackIntentsTest {
    @Test fun newerPauseWinsEvenBeforeOlderQueueCommandDecidesToPlay() {
        val intents = PlaybackIntents()
        val queued = intents.accept()
        val pause = intents.accept(false)
        intents.propose(queued, true)
        assertEquals(PlaybackIntents.Choice(false, pause), intents.choose(queued, true))
    }
    @Test fun pendingPlaySurvivesPassiveAttachButFulfilledPlayDoesNotReplay() {
        val intents = PlaybackIntents()
        val play = intents.accept(true)
        assertEquals(PlaybackIntents.Choice(true, play), intents.choose(fallback = false))
        intents.applied(intents.choose(fallback = false))
        assertFalse(intents.choose(fallback = false).play)
        assertNull(intents.pending())
    }
    @Test fun latestExplicitRequestWinsBeforeReadinessInBothOrders() {
        for (last in listOf(false, true)) {
            val intents = PlaybackIntents()
            intents.accept(!last); intents.accept(last)
            assertEquals(last, intents.choose(fallback = !last).play)
        }
    }
    @Test fun laterSelectionCanPlayAfterPauseButOldSelectionCannot() {
        val intents = PlaybackIntents()
        val old = intents.accept()
        intents.accept(false)
        intents.propose(old, true)
        assertFalse(intents.choose(old, true).play)
        val selected = intents.accept()
        intents.propose(selected, true)
        assertTrue(intents.choose(selected, false).play)
    }
    @Test fun staleAppliedAcknowledgmentCannotConsumeNewRequest() {
        val intents = PlaybackIntents()
        intents.accept(true)
        val old = intents.choose(fallback = false)
        intents.accept(false)
        intents.applied(old)
        assertFalse(intents.pending()!!.play)
    }
    @Test fun toggleUsesPendingThenObservedRequestedState() {
        val intents = PlaybackIntents()
        intents.accept(true)
        intents.toggle()
        assertFalse(intents.pending()!!.play)
        intents.applied(intents.pending()!!)
        intents.observe(true) // playWhenReady remains true during native suppression/buffering.
        intents.toggle()
        assertFalse(intents.pending()!!.play)
    }
    @Test fun nativeSetterLinearizesBeforeConcurrentPauseAcceptance() {
        val intents = PlaybackIntents()
        val play = intents.accept(true)
        val trying = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        var pauser: Thread? = null
        intents.apply(play, false) { value ->
            assertTrue(value)
            pauser = thread { trying.countDown(); intents.accept(false); accepted.countDown() }
            assertTrue(trying.await(5, TimeUnit.SECONDS))
            assertFalse("Pause cannot be accepted between choosing true and its native assignment", accepted.await(50, TimeUnit.MILLISECONDS))
            intents.observe(true) // Native callbacks may reenter the monitor.
        }
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        pauser!!.join()
        assertFalse(intents.pending()!!.play)
    }
    @Test fun failedNativeSetterDoesNotAcknowledgeUnfulfilledPlay() {
        val intents = PlaybackIntents()
        intents.accept(true)
        assertTrue(runCatching { intents.apply(fallback = false) { error("Native setter failed") } }.isFailure)
        assertTrue(intents.pending()!!.play)
    }
    @Test fun pauseAfterAutomaticEndDecisionSurvivesPreparationUntilFinalApply() {
        val intents = PlaybackIntents()
        intents.accept(true)
        intents.applied(intents.pending()!!)
        val end = intents.accept()
        intents.accept(false)
        intents.preparing { assertTrue(it) }
        assertFalse(intents.pending()!!.play)
        intents.apply(end, true) { assertFalse(it) }
        assertNull(intents.pending())
    }
    @Test fun fulfilledPlayDoesNotOverrideLaterPausePolicyOrHardErrorFallback() {
        val intents = PlaybackIntents()
        intents.accept(true)
        intents.applied(intents.pending()!!)
        assertFalse(intents.choose(fallback = false).play)
        val policy = intents.accept()
        intents.apply(policy, false) { assertFalse(it) }
        assertNull(intents.pending())
    }
    @Test fun recoveryRetiresOnlyItsOldSourceAndKeepsNewerSessionIntent() {
        val intents = PlaybackIntents()
        val old = intents.accept(true)
        intents.bindSource(old, "damaged")
        val recovery = intents.accept()
        intents.discardForRecovery("damaged", recovery, bindUnassigned = false)
        assertFalse(intents.choose(old, false).play)
        val newer = intents.accept(true)
        intents.bindSource(newer, "damaged")
        intents.discardForRecovery("damaged", recovery, bindUnassigned = false)
        assertEquals(newer, intents.pending()!!.revision)
        intents.accept(false)
        intents.discardForRecovery("damaged", Long.MAX_VALUE, bindUnassigned = true)
        assertFalse(intents.pending()!!.play)
    }

    @Test fun recoveryOfInactiveSourceDoesNotConsumeUnrelatedPlay() {
        val intents = PlaybackIntents()
        val play = intents.accept(true)
        intents.bindSource(play, "unrelated")
        intents.discardForRecovery("damaged", intents.accept(), bindUnassigned = false)
        assertTrue(intents.pending()!!.play)
        assertEquals("unrelated", intents.pending()!!.source)
    }

    @Test fun queuedPausedSwitchThenPlayBindsAtItsOwnCommandEvenIfEarlierActivationAppliedIt() {
        val intents = PlaybackIntents()
        val switch = intents.accept()
        val play = intents.accept(true)
        // Earlier activation may apply the newer global request before the switch reaches the actor.
        intents.apply(fallback = false) { assertTrue(it) }
        intents.discardForRecovery("old", intents.currentRevision(), bindUnassigned = false)
        intents.propose(switch, false, "new")
        assertTrue(intents.choose(switch, false).play)
        intents.bindSource(play, "new")
        intents.discardForRecovery("old", intents.currentRevision(), bindUnassigned = false)
        assertTrue(intents.choose(play, false).play)
        intents.discardForRecovery("new", intents.currentRevision(), bindUnassigned = false)
        assertFalse(intents.choose(play, false).play)
    }

    @Test fun olderActorCommandCannotBindANewerPendingRequestToItsStaleSource() {
        val intents = PlaybackIntents()
        val old = intents.accept(true)
        val new = intents.accept(true)
        intents.bindSource(old, "old")
        assertNull(intents.pending()!!.source)
        intents.bindSource(new, "new")
        assertEquals("new", intents.pending()!!.source)
    }
    @Test fun acceptedRecoveryPreservesNewPlayDuringPreAcquisitionWaitThenClaimsOnce() {
        val intents = PlaybackIntents()
        val request = intents.recoveryRequested("damaged")
        val play = intents.accept(true)
        intents.bindSource(play, "damaged")
        intents.discardForRecovery("damaged", intents.currentRevision(), bindUnassigned = false)
        assertTrue(intents.pending()!!.play)
        val cutoff = intents.claimRecovery(request, "damaged")
        intents.applied(intents.pending()!!)
        assertTrue("A newer applied request still determines the initial recovery resume", intents.choose(cutoff, false).play)
        assertTrue(runCatching { intents.claimRecovery(request, "damaged") }.isFailure)
        intents.finishRecovery(request) { false }
        assertTrue(runCatching { intents.claimRecovery(request, "damaged") }.isFailure)
    }

    @Test fun failedRecoveryRetiresItsPreservedPlayButNotUnrelatedOrPause() {
        for (source in listOf("damaged", "other")) {
            val intents = PlaybackIntents()
            val request = intents.recoveryRequested("damaged")
            val play = intents.accept(true)
            intents.bindSource(play, source)
            intents.finishRecovery(request) { it == "damaged" }
            assertEquals(source == "other", intents.choose(play, false).play)
        }
        val intents = PlaybackIntents()
        val request = intents.recoveryRequested("damaged")
        val pause = intents.accept(false)
        intents.finishRecovery(request) { true }
        assertEquals(pause, intents.pending()!!.revision)
        assertFalse(intents.pending()!!.play)
    }

    @Test fun unidentifiedRecoveryDoesNotGrantAllSourcesRetentionAndForeignMarkerIsRejected() {
        val intents = PlaybackIntents()
        val request = intents.recoveryRequested(null)
        val play = intents.accept(true)
        intents.bindSource(play, "damaged")
        intents.discardForRecovery("damaged", intents.currentRevision(), bindUnassigned = false)
        assertFalse(intents.choose(play, false).play)
        assertTrue(runCatching { PlaybackIntents().claimRecovery(request, "damaged") }.isFailure)
        intents.claimRecovery(request, "damaged")
        val newer = intents.accept(true)
        intents.bindSource(newer, "damaged")
        intents.discardForRecovery("damaged", intents.currentRevision(), bindUnassigned = false)
        assertTrue(intents.pending()!!.play)
        intents.finishRecovery(request) { true }
        assertNull(intents.pending())
    }

    @Test fun finishingOneRecoveryDoesNotRetirePlayOwnedByAnotherActiveSession() {
        val intents = PlaybackIntents()
        val first = intents.recoveryRequested("damaged")
        val second = intents.recoveryRequested("damaged")
        val play = intents.accept(true)
        intents.bindSource(play, "damaged")
        intents.finishRecovery(second) { true }
        assertEquals(play, intents.pending()!!.revision)
        intents.claimRecovery(first, "damaged")
        assertTrue(runCatching { intents.claimRecovery(first, "damaged") }.isFailure)
        intents.discardForRecovery("damaged", intents.currentRevision(), bindUnassigned = false)
        assertEquals(play, intents.pending()!!.revision)
        intents.finishRecovery(first) { true }
        assertNull(intents.pending())
    }

}
