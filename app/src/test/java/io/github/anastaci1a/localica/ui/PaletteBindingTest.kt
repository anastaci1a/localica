package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.PhaseBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** #600 U08.02a witness: the player ink keeps value and slope across a changed target and a lyrics reversal. */
class PaletteBindingTest {
    @Test fun reverseLyricsAtChangedInk() {
        val ink = PhaseBinding(1f)
        val phase = { i: Int -> if (i < 20) i / 40f else (.5f - (i - 20) / 40f).coerceAtLeast(0f) }
        val light = (0..44).map { i -> ink.resolve(if (i < 8) 1f else 0f, phase(i), if (i < 20) 1f else 0f).also(ink::present).value }
        assertEquals(0f, light.last(), 0f)
        val steps = light.zipWithNext { a, b -> b - a }
        steps.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("slope jumps at frame ${i + 1}: $a -> $b", abs(b - a) <= .01f) }
    }

    @Test fun aChangedIntentKeepsValueAndSlopeAtItsPhase() {
        val ink = PhaseBinding(1f)
        ink.present(ink.resolve(0f, .2f, 1f))
        val before = ink.resolve(0f, .6f, 1f).also(ink::present)
        // Reversal at the same phase, then a changed target at the same phase: same value and slope per unit of travel.
        val reversed = ink.resolve(0f, .6f, 0f)
        assertEquals(before.value, reversed.value, 1e-6f); assertEquals(before.slope, reversed.slope, 1e-5f)
        val retargeted = ink.resolve(1f, .6f, 1f)
        assertEquals(before.value, retargeted.value, 1e-6f); assertEquals(before.slope, retargeted.slope, 1e-5f)
        assertEquals("the end of the phase lands with zero slope", 0f, ink.resolve(0f, .9995f, 1f).slope, 0f)
    }
}
