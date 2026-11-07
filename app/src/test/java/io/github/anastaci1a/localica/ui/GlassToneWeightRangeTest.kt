package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/** #633: the Brightness weighting range 1–8 keeps ordered compression and leaves every pre-#633 setting unchanged. */
class GlassToneWeightRangeTest {
    private val trims = (0..25).map { it / 50f } + listOf(.001f, .125f, .249f, .499f)
    private val weights = ((0..70).map { 1f + it / 10f } + listOf(1.05f, 1.95f, 1.999f, 2.001f, 7.999f) +
        (0..GlassContrastWeightSteps + 1).map {
            contrastWeightFromSlider(sliderValue(it / (GlassContrastWeightSteps + 1f), 1f..8f, GlassContrastWeightCurve))
        }).distinct()

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

    @Test fun sliderGivesTheLowEndATrackThirdAndStoresTheDefaultExactly() {
        val stored = (0..GlassContrastWeightSteps + 1).map {
            contrastWeightFromSlider(sliderValue(it / (GlassContrastWeightSteps + 1f), 1f..8f, GlassContrastWeightCurve))
        }
        assertEquals(1f, stored.first(), 0f)
        assertEquals(8f, stored.last(), 0f)
        assertEquals(2f, stored[(GlassContrastWeightSteps + 1) / 3], 0f)
        assertTrue("Steps strictly increase: $stored", stored.zipWithNext().all { (a, b) -> b > a })
        assertEquals("1–2 keeps its 20 steps", 21, stored.count { it <= 2f })
        assertEquals(1f / 3f, sliderPosition(2f, 1f..8f, GlassContrastWeightCurve), 1e-6f)
    }

    @Test fun normalizationKeepsTheNewRangeAndTheDefault() {
        assertEquals(2f, GlassAppearance().contrastWeight, 0f)
        assertEquals(2f, InterfaceSettings().glass.contrastWeight, 0f)
        for (w in listOf(1f, 1.2f, 1.95f, 2f, 2.5f, 4.321f, 8f))
            assertEquals(w, GlassAppearance(contrastWeight = w).normalized().contrastWeight, 0f)
        assertEquals(8f, GlassAppearance(contrastWeight = 12f).normalized().contrastWeight, 0f)
        assertEquals(1f, GlassAppearance(contrastWeight = .5f).normalized().contrastWeight, 0f)
        assertEquals(2f, GlassAppearance(contrastWeight = Float.NaN).normalized().contrastWeight, 0f)
        assertEquals(2f, GlassAppearance(contrastWeight = Float.POSITIVE_INFINITY).normalized().contrastWeight, 0f)
    }
}
