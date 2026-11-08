package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshots.Snapshot
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Native mutation cancellation must also balance the real Engine observer. */
class SettingsScrollObservationTest {
    private suspend fun frame(h: EngineHarness, seconds: Double) {
        h.advance(seconds)
        Snapshot.sendApplyNotifications()
        yield()
    }

    @Test fun cancellingAnAdmittedNativeScrollBalancesTheObservedOwnerAtItsOriginalDeadline() = runBlocking {
        withTimeout(5000) {
            for ((access, durationMs) in listOf(MotionAccess.Standard to 262, MotionAccess.Faster to 175)) EngineHarness(access).use { h ->
                val evidence = BoundedEvidence(100)
                EngineObservation(h.clock, evidence).use { observer ->
                    val scroll = ScrollState(0)
                    val moving = launch(start = CoroutineStart.UNDISPATCHED) { SettingsScrollMotion(h.clock, scroll).scrollTo(120) }
                    frame(h, .05)
                    val shown = scroll.value
                    assertTrue(shown in 1..119)
                    moving.cancelAndJoin()
                    assertEquals(1, h.clock.activeCount)
                    assertEquals(1, observer.continuity.registeredCount)
                    frame(h, (durationMs - 51) / 1000.0)
                    assertEquals(1, h.clock.activeCount)
                    assertEquals(shown, scroll.value)
                    frame(h, .001)
                    assertEquals(shown, scroll.value)
                    assertEquals(0, h.clock.activeCount)
                    assertEquals(0, observer.continuity.registeredCount)
                    assertEquals(emptyList<Violation>(), evidence.violations)
                }
            }
        }
    }

    @Test fun successorAcquiresAndAdvancesBeforeTheCancelledPredecessorRests() = runBlocking {
        withTimeout(5000) {
            for ((access, durationMs) in listOf(MotionAccess.Standard to 262, MotionAccess.Faster to 175)) EngineHarness(access).use { h ->
                val evidence = BoundedEvidence(100)
                EngineObservation(h.clock, evidence).use { observer ->
                    val scroll = ScrollState(0)
                    val owner = SettingsScrollMotion(h.clock, scroll)
                    val old = launch(start = CoroutineStart.UNDISPATCHED) { owner.scrollTo(120) }
                    frame(h, .05)
                    val shown = scroll.value
                    old.cancelAndJoin()
                    val admission = h.now
                    val next = launch(start = CoroutineStart.UNDISPATCHED) { owner.scrollTo(240) }
                    assertEquals("Successor acquires without advancing time or waiting for old Rest", admission, h.now)
                    assertEquals(2, h.clock.activeCount)
                    assertEquals(2, observer.continuity.registeredCount)
                    frame(h, .02)
                    assertTrue("Successor owns the native mutator while its predecessor still finishes", scroll.value > shown)
                    frame(h, (durationMs - 71) / 1000.0)
                    assertEquals(2, h.clock.activeCount)
                    frame(h, .001)
                    assertEquals(1, h.clock.activeCount)
                    assertEquals(1, observer.continuity.registeredCount)
                    frame(h, .05)
                    next.join()
                    assertEquals(240, scroll.value)
                    assertEquals(0, h.clock.activeCount)
                    assertEquals(0, observer.continuity.registeredCount)
                    assertEquals(emptyList<Violation>(), evidence.violations)
                }
            }
        }
    }

    @Test fun reducedSuccessorsSnapAndRetireImmediatelyWithoutAClockFrame() = runBlocking {
        withTimeout(5000) { EngineHarness(MotionAccess.Reduced).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val scroll = ScrollState(0)
                val owner = SettingsScrollMotion(h.clock, scroll)
                owner.scrollTo(120)
                assertEquals(120, scroll.value)
                owner.scrollTo(240)
                assertEquals(240, scroll.value)
                assertEquals(0, h.awaits)
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        } }
    }
}
