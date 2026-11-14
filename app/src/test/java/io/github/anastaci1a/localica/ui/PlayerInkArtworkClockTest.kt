package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.engine.PhaseBinding
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.MotionFloat
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** #578: a song change eases the ink on the artwork blend's clock and retargets without jumps. */
class PlayerInkArtworkClockTest {
    private fun step(t: PhaseBinding, target: Color, clock: Float): Float =
        t.resolve(if (target == Color.White) 1f else 0f, clock, 1f, clock = 1).also(t::present).value

    @Test fun easesFromTheStartOfTheClockToItsEnd() {
        val t = PhaseBinding(0f)
        val values = (0..20).map { step(t, Color.White, it / 20f) }
        assertEquals(0f, values.first(), 1e-6f); assertEquals(1f, values.last(), 1e-6f)
        values.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-6f && b - a <= .1f) }
    }

    @Test fun aLateDestinationEasesOverTheRemainingClockWithoutJumping() {
        val t = PhaseBinding(0f)
        (0..8).forEach { assertEquals(0f, step(t, Color.Black, it / 20f), 1e-6f) }
        var last = 0f
        (9..20).forEach { val v = step(t, Color.White, it / 20f); assertTrue("jump $last -> $v", abs(v - last) <= .2f); last = v }
        assertEquals(1f, last, 1e-6f)
    }

    @Test fun aRapidSkipRestartsTheClockAndRetargetsFromTheShownInk() {
        val t = PhaseBinding(0f)
        var last = 0f
        (0..10).forEach { last = step(t, Color.White, it / 20f) }
        assertTrue(last in .1f..0.9f)
        // A new change: the clock restarts at 0 and the destination flips back.
        (0..20).forEach { val v = step(t, Color.Black, it / 20f); assertTrue("jump $last -> $v", abs(v - last) <= .2f); last = v }
        assertEquals(0f, last, 1e-6f)
        // And the restarted clock with an unchanged destination continues from where it is, not from its old start.
        val t2 = PhaseBinding(0f)
        (0..10).forEach { last = step(t2, Color.White, it / 20f) }
        (0..8).forEach { val v = step(t2, Color.White, it / 20f); assertTrue("restart jump $last -> $v", abs(v - last) <= .2f); last = v }
    }

    @Test fun settledClockAppliesTheTargetAtOnce() {
        val t = PhaseBinding(0f)
        assertEquals(1f, step(t, Color.White, 1f), 0f)
    }

    @Test fun aDestinationAfterExpansionSettlesUsesTheSharedValueClock() {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 1f, 1f)
            val binding = PhaseBinding(1f, motion)
            fun sample(target: Float) = binding.resolve(target, 1f, 1f, clock = 2).also(binding::present).value
            assertEquals(1f, sample(1f), 0f)
            assertEquals("The arrival frame keeps the presented ink", 1f, sample(0f), 0f)
            val values = (0..40).map { h.advance(1.0 / 120); sample(0f) }
            assertTrue(values.count { it in .01f..0.99f } > 10)
            values.zipWithNext().forEach { (a, b) -> assertTrue(b <= a && a - b < .1f) }
            assertEquals(0f, values.last(), 0f)
            assertEquals("No clock remains awake", 0, h.clock.activeCount)
        }
    }

    @Test fun anOwnerPhaseTakesOverALateEaseWithoutJumpingAndLandsAtItsEndpoint() {
        EngineHarness().use { h ->
            val binding = PhaseBinding(1f, MotionFloat(h.clock, 1f, 1f))
            fun sample(phase: Float, end: Float) = binding.resolve(0f, phase, end, clock = 2).also(binding::present).value
            sample(0f, 0f)
            h.advance(.1)
            val before = sample(0f, 0f)
            assertTrue(before in .01f..0.99f)
            assertEquals(before, sample(0f, 1f), .0001f)
            (1..20).forEach { h.advance(1.0 / 120); sample(it / 20f, 1f) }
            assertEquals(0f, sample(1f, 1f), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }
}
