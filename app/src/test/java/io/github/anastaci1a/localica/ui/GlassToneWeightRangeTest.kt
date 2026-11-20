package io.github.anastaci1a.localica.ui

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/** The generic tone kernel keeps its numerical domain and exact ordered-compression law. */
class GlassToneWeightRangeTest {
    private val trims = (0..25).map { it / 50f } + listOf(.001f, .125f, .249f, .499f)
    private val weights = ((0..70).map { 1f + it / 10f } + listOf(1.05f, 1.95f, 1.999f, 2.001f, 7.999f) +
        listOf(1f, 1.005f, 1.017f, 1.035f, 1.058f, 1.086f, 1.119f, 1.156f, 1.197f, 1.243f, 1.293f, 1.347f, 1.405f, 1.466f, 1.532f, 1.601f, 1.674f, 1.75f, 1.83f, 1.913f, 2f, 2.09f, 2.184f, 2.281f, 2.381f, 2.485f, 2.592f, 2.702f, 2.815f, 2.931f, 3.051f, 3.173f, 3.299f, 3.428f, 3.56f, 3.695f, 3.832f, 3.973f, 4.117f, 4.264f, 4.413f, 4.566f, 4.722f, 4.88f, 5.041f, 5.205f, 5.372f, 5.542f, 5.715f, 5.89f, 6.068f, 6.249f, 6.433f, 6.619f, 6.808f, 7f, 7.195f, 7.392f, 7.592f, 7.795f, 8f)).distinct()

    @Test fun everyTrimWeightAndPolarityStaysOrderedFiniteAndBounded() {
        for (t in trims) for (p in weights) for (mix in listOf(0f, .25f, .5f, .75f, 1f)) {
            val tone = GlassTone.mixed(t, p, mix)
            var last = -1f
            for (sample in 0..1024) {
                val x = sample / 1024f
                val y = tone.channel(x)
                assertTrue("$tone at $x: $y", y.isFinite())
                assertTrue("$tone at $x: $last -> $y", y >= last - 1e-6f)
                assertTrue("$tone at $x: $y", y in -1e-6f..1f + 1e-6f)
                last = y
            }
            // Bytes, which the API 26 fallback prepares, never reverse either.
            for (b in 1..255) assertTrue("$tone byte $b", tone.byteChannel(b) >= tone.byteChannel(b - 1))
        }
    }

    @Test fun theUnlimitedLawWouldInvertAboveWeightTwoAtFullTrim() {
        fun raw(x: Float, t: Float, p: Float) = x - t * x.pow(p)
        assertTrue(raw(.9f, .5f, 8f) > raw(1f, .5f, 8f))
        assertTrue(GlassTone(.5f, 8f, true).channel(.9f) < GlassTone(.5f, 8f, true).channel(1f))
        assertTrue(GlassTone(.5f, 8f, false).channel(0f) < GlassTone(.5f, 8f, false).channel(.1f))
    }

    @Test fun weightsAboveTwoTakeEffectWithTheLimitedTrim() {
        assertEquals(.5f - .25f * .5f.pow(4f), GlassTone(.25f, 4f, true).channel(.5f), 0f)
        assertNotEquals(GlassTone(.25f, 2f, true).channel(.5f), GlassTone(.25f, 4f, true).channel(.5f))
        // t·p = 4 > 1: the trim becomes 1/8, so white maps to 7/8 and black lifts to 1/8.
        assertEquals(1f - 1f / 8f, GlassTone(.5f, 8f, true).channel(1f), 1e-7f)
        assertEquals(1f / 8f, GlassTone(.5f, 8f, false).channel(0f), 1e-7f)
        // t·p = .8 ≤ 1: the requested trim applies unchanged.
        assertEquals(.9f, GlassTone(.1f, 8f, true).channel(1f), 1e-7f)
        assertEquals(.125f, effectiveGlassTrim(.125f, 8f), 0f)
        assertEquals(.125f, effectiveGlassTrim(.5f, 8f), 0f)
    }

    @Test fun everyPreviousSettingKeepsItsExactOutput() {
        fun previous(x: Float, t: Float, p: Float, light: Boolean): Float {
            val tt = t.coerceIn(0f, .5f); val pp = p.coerceIn(1f, 2f)
            return if (light) x - tt * x.pow(pp) else x + tt * (1f - x).pow(pp)
        }
        for (t in (0..50).map { it / 100f }) for (p in (0..20).map { 1f + it / 20f } + listOf(1.2f, 1.4f, 1.5f, 1.7f)) {
            assertEquals(t, effectiveGlassTrim(t, p), 0f)
            for (light in listOf(false, true)) for (sample in 0..255) {
                val x = sample / 255f
                assertEquals("$t $p $light $x", previous(x, t, p, light), GlassTone(t, p, light).channel(x), 0f)
            }
        }
    }

}
