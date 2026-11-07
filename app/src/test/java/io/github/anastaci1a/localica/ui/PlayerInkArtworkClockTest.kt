package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.engine.PhaseBinding
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
}
