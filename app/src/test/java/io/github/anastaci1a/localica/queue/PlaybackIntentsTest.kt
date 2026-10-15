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
}
