package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class LyricsSourcePhaseTest {
    private val ms = 1_000_000L

    @Test fun mappingHasSeparatedOverlapAndExactEndpoints() {
        assertEquals(0f, LyricsSourcePhase.preparation(0f), 0f)
        assertEquals(0f, LyricsSourcePhase.popup(0f), 0f)
        assertEquals(1f, LyricsSourcePhase.preparation(1f), 0f)
        assertEquals(1f, LyricsSourcePhase.popup(1f), 0f)
        assertTrue(LyricsSourcePhase.preparation(.25f) > .5f)
        assertEquals(0f, LyricsSourcePhase.popup(.25f), 0f)
        assertTrue(LyricsSourcePhase.preparation(.3f) < 1f)
        assertTrue(LyricsSourcePhase.popup(.3f) > 0f)
        assertEquals(1f, LyricsSourcePhase.preparation(.4f), 0f)
    }

    @Test fun mappingIsMonotonicWithZeroScalarEndpointSlopes() {
        var lastPreparation = 0f
        var lastPopup = 0f
        for (i in 0..1000) {
            val q = i / 1000f
            val preparation = LyricsSourcePhase.preparation(q)
            val popup = LyricsSourcePhase.popup(q)
            assertTrue(preparation >= lastPreparation)
            assertTrue(popup >= lastPopup)
            lastPreparation = preparation
            lastPopup = popup
        }
        val h = .0001f
        assertEquals(0f, LyricsSourcePhase.preparation(h) / h, .01f)
        assertEquals(0f, (1f - LyricsSourcePhase.preparation(.4f - h)) / h, .01f)
        assertEquals(0f, LyricsSourcePhase.popup(.25f + h) / h, .01f)
        assertEquals(0f, (1f - LyricsSourcePhase.popup(1f - h)) / h, .01f)
    }

    @Test fun reversalAndSpeedChangeKeepSampledValueAndVelocity() {
        for (at in listOf(35L, 100L, 240L, 600L)) {
            val owner = LyricsSourcePhaseController()
            val first = owner.retarget(true, false, 1f, 0L)
            val before = owner.advance(first, at * ms)
            val second = owner.retarget(false, false, 1f, at * ms)
            assertEquals(before.q, owner.frame.q, .000001f)
            assertEquals(before.velocity, owner.frame.velocity, .000001f)
            val current = owner.advance(second, (at + 11) * ms)
            owner.retarget(false, false, 1.8f, (at + 11) * ms)
            assertEquals(current.q, owner.frame.q, .000001f)
            assertEquals(current.velocity, owner.frame.velocity, .000001f)
        }
    }

    @Test fun openingHoldPreservesCrossingVelocityAcrossLongWait() {
        val owner = LyricsSourcePhaseController()
        val token = owner.retarget(true, false, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        val held = owner.advance(token, 2_000 * ms)
        assertEquals(LyricsSourcePhaseController.Status.Held, held.status)
        assertEquals(.25f, held.q, 0f)
        assertTrue(held.velocity > 0f)
        assertEquals(held, owner.advance(token, 9_000 * ms))
        assertTrue(owner.release(token, owner.frame.crossing, 9_000 * ms))
        assertEquals(held.q, owner.frame.q, .000001f)
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
        assertTrue(owner.advance(token, 9_017 * ms).popup > 0f)
    }

    @Test fun closingHoldRestoresBeforeNeckRejoinsAndRejectsStaleAck() {
        val owner = LyricsSourcePhaseController()
        owner.retarget(true, true, 1f, 0L)
        val closing = owner.retarget(false, false, 1f, 0L, LyricsSourcePhaseController.Gate.RestoredDraw)
        val held = owner.advance(closing, 1_000 * ms)
        assertEquals(LyricsSourcePhaseController.Status.Held, held.status)
        assertEquals(0f, held.popup, 0f)
        assertTrue(held.preparation > .5f)
        assertTrue(held.velocity < 0f)
        val opening = owner.retarget(true, false, 1f, 1_000 * ms, gate = null)
        assertEquals(held.q, owner.frame.q, .000001f)
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
        assertFalse(owner.release(closing, owner.frame.crossing, 1_100 * ms))
        assertEquals(opening, owner.advance(closing, 9_000 * ms).generation)
        assertEquals(LyricsSourcePhaseController.Status.Settled, owner.advance(opening, 9_000 * ms).status)
        assertEquals(1f, owner.frame.popup, 0f)
    }

    @Test fun heldSpeedChangeKeepsMomentumAndRequiresNewAcknowledgment() {
        val owner = LyricsSourcePhaseController()
        val old = owner.retarget(true, false, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        val held = owner.advance(old, 500 * ms)
        val next = owner.retarget(true, false, 2f, 500 * ms, LyricsSourcePhaseController.Gate.OpeningReady)
        assertEquals(held.q, owner.frame.q, 0f)
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
        assertEquals(LyricsSourcePhaseController.Status.Held, owner.frame.status)
        assertFalse(owner.release(old, owner.frame.crossing, 600 * ms))
        assertTrue(owner.release(next, owner.frame.crossing, 600 * ms))
    }

    @Test fun reversalCanRetainRestoredDrawSafetyBarrierUntilAcknowledged() {
        val owner = LyricsSourcePhaseController()
        owner.retarget(true, true, 1f, 0L)
        val closing = owner.retarget(false, false, 1f, 0L, LyricsSourcePhaseController.Gate.RestoredDraw)
        val held = owner.advance(closing, 1_000 * ms)
        val opening = owner.retarget(true, false, .1f, 1_000 * ms,
            LyricsSourcePhaseController.Gate.RestoredDraw)
        assertEquals(LyricsSourcePhaseController.Status.Held, owner.advance(opening, 5_000 * ms).status)
        assertEquals(held.q, owner.frame.q, 0f)
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
        assertFalse(owner.release(closing, owner.frame.crossing, 5_000 * ms))
        assertTrue(owner.release(opening, owner.frame.crossing, 5_000 * ms))
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
    }

    @Test fun coarseAndIrregularFramesFindSameGateVelocity() {
        val coarse = LyricsSourcePhaseController()
        val fine = LyricsSourcePhaseController()
        val a = coarse.retarget(true, false, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        val b = fine.retarget(true, false, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        val expected = coarse.advance(a, 2_000 * ms)
        for (t in listOf(1L, 13L, 47L, 81L, 109L, 175L, 501L, 2_000L)) fine.advance(b, t * ms)
        assertEquals(expected.q, fine.frame.q, 0f)
        assertEquals(expected.velocity, fine.frame.velocity, .0001f)
    }

    @Test fun longFrameCannotSkipCrossingAndReturnDuringReversal() {
        val owner = LyricsSourcePhaseController()
        owner.retarget(true, true, 1f, 0L)
        val closing = owner.retarget(false, false, 1f, 0L)
        assertTrue(owner.advance(closing, 220 * ms).q > .25f)
        val reopening = owner.retarget(true, false, .1f, 220 * ms,
            LyricsSourcePhaseController.Gate.RestoredDraw)
        val held = owner.advance(reopening, 30_000 * ms)
        assertEquals(LyricsSourcePhaseController.Status.Held, held.status)
        assertEquals(.25f, held.q, 0f)
        assertTrue(held.velocity < 0f)
    }

    @Test fun reducedMotionHonorsGateThenUsesExactEndpoints() {
        val owner = LyricsSourcePhaseController()
        val opening = owner.retarget(true, true, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        assertEquals(LyricsSourcePhaseController.Status.Held, owner.frame.status)
        assertEquals(.25f, owner.frame.q, 0f)
        owner.release(opening, owner.frame.crossing, ms)
        assertEquals(1f, owner.frame.q, 0f)
        val closing = owner.retarget(false, true, 1f, ms, LyricsSourcePhaseController.Gate.RestoredDraw)
        assertEquals(.25f, owner.frame.q, 0f)
        owner.release(closing, owner.frame.crossing, 2 * ms)
        assertEquals(0f, owner.frame.q, 0f)
        assertEquals(0f, owner.frame.velocity, 0f)
        assertEquals(LyricsSourcePhaseController.Status.Settled, owner.frame.status)
    }

    @Test fun prematureAcknowledgmentFailsAndResetInvalidatesWork() {
        val owner = LyricsSourcePhaseController()
        val token = owner.retarget(true, false, 1f, 0L, LyricsSourcePhaseController.Gate.OpeningReady)
        assertFalse(owner.release(token, owner.frame.crossing, 10 * ms))
        owner.advance(token, 300 * ms)
        val held = owner.frame
        assertTrue(owner.release(token, held.crossing, 300 * ms))
        assertFalse(owner.release(token, held.crossing, 300 * ms))
        owner.reset()
        assertFalse(owner.release(token, held.crossing, 400 * ms))
        assertEquals(0f, owner.advance(token, 10_000 * ms).q, 0f)
        assertEquals(LyricsSourcePhaseController.Status.Settled, owner.frame.status)
    }

    @Test fun oneOpenTargetCanCrossDownThenUpWithSeparateAcknowledgments() {
        val owner = LyricsSourcePhaseController()
        owner.retarget(true, true, 1f, 0L)
        val closing = owner.retarget(false, false, 1f, 0L)
        owner.advance(closing, 220 * ms)
        val opening = owner.retarget(true, false, .1f, 220 * ms,
            LyricsSourcePhaseController.Gate.RestoredDraw)
        val down = owner.advance(opening, 30_000 * ms)
        assertEquals(LyricsSourcePhaseController.Gate.RestoredDraw, down.heldGate)
        assertTrue(down.velocity < 0f)
        assertTrue(owner.release(opening, down.crossing, 30_000 * ms))
        assertEquals(LyricsSourcePhaseController.Status.Moving, owner.frame.status)
        assertEquals(down.velocity, owner.frame.velocity, .000001f)
        val up = owner.advance(opening, 60_000 * ms)
        assertEquals(LyricsSourcePhaseController.Gate.OpeningReady, up.heldGate)
        assertTrue(up.velocity > 0f)
        assertTrue(up.crossing > down.crossing)
        assertFalse(owner.release(opening, down.crossing, 60_000 * ms))
        assertTrue(owner.release(opening, up.crossing, 60_000 * ms))
        assertEquals(LyricsSourcePhaseController.Status.Moving, owner.frame.status)
        assertEquals(1f, owner.advance(opening, 90_000 * ms).q, 0f)
    }

    @Test fun oneCloseTargetCanCrossUpThenDownWithSeparateAcknowledgments() {
        val owner = LyricsSourcePhaseController()
        val opening = owner.retarget(true, false, 1f, 0L)
        assertTrue(owner.advance(opening, 60 * ms).q < .25f)
        val closing = owner.retarget(false, false, .1f, 60 * ms,
            LyricsSourcePhaseController.Gate.OpeningReady)
        val up = owner.advance(closing, 30_000 * ms)
        assertEquals(LyricsSourcePhaseController.Gate.OpeningReady, up.heldGate)
        assertTrue(up.velocity > 0f)
        assertTrue(owner.release(closing, up.crossing, 30_000 * ms))
        assertEquals(LyricsSourcePhaseController.Status.Moving, owner.frame.status)
        val down = owner.advance(closing, 60_000 * ms)
        assertEquals(LyricsSourcePhaseController.Gate.RestoredDraw, down.heldGate)
        assertTrue(down.velocity < 0f)
        assertFalse(owner.release(closing, up.crossing, 60_000 * ms))
        assertTrue(owner.release(closing, down.crossing, 60_000 * ms))
        assertEquals(0f, owner.advance(closing, 90_000 * ms).q, 0f)
    }

    @Test fun canceledOpeningUsesExplicitImpulseWithoutAdmittingModal() {
        val owner = LyricsSourcePhaseController()
        val opening = owner.retarget(true, false, 1f, 0L,
            LyricsSourcePhaseController.Gate.OpeningReady)
        val held = owner.advance(opening, 500 * ms)
        assertFalse(owner.rejectOpening(opening, held.crossing, 500 * ms))
        val closing = owner.retarget(false, false, 1f, 500 * ms)
        assertEquals(held.velocity, owner.frame.velocity, .000001f)
        assertFalse(owner.rejectOpening(opening, held.crossing, 500 * ms))
        assertTrue(owner.rejectOpening(closing, held.crossing, 500 * ms))
        assertEquals(held.q, owner.frame.q, 0f)
        assertEquals(0f, owner.frame.velocity, 0f)
        assertEquals(LyricsSourcePhaseController.Status.Moving, owner.frame.status)
        assertFalse(owner.release(closing, held.crossing, 500 * ms))
        assertTrue(owner.advance(closing, 550 * ms).q < .25f)
        val reopening = owner.retarget(true, false, 1f, 550 * ms)
        val readmission = owner.advance(reopening, 3_000 * ms)
        assertEquals(LyricsSourcePhaseController.Gate.OpeningReady, readmission.heldGate)
        assertTrue(readmission.crossing > held.crossing)
    }
}
