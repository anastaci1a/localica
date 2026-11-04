package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class GlassToneTest {
    @Test fun bothPolaritiesRemainOrderedAndBoundedForEverySetting() {
        for (trim in 0..10) for (weight in 0..20) for (light in listOf(false, true)) {
            val tone = GlassTone(trim / 20f, 1f + weight / 20f, light)
            var last = -1f
            for (sample in 0..1024) {
                val result = tone.channel(sample / 1024f)
                assertTrue("$tone at $sample: $last -> $result", result >= last - .000001f)
                assertTrue(result >= (if (light) 0f else tone.trim) - .000001f)
                assertTrue(result <= (if (light) 1f - tone.trim else 1f) + .000001f)
                last = result
            }
        }
    }

    @Test fun weightingPreservesTheSafeEndAndInverseIsSymmetric() {
        val darken = GlassTone(.25f, 2f, true)
        val lift = darken.copy(lightInk = false)
        assertEquals(0f, darken.channel(0f), 0f)
        assertEquals(.75f, darken.channel(1f), 0f)
        assertEquals(.25f, lift.channel(0f), 0f)
        assertEquals(1f, lift.channel(1f), 0f)
        assertTrue(.1f - darken.channel(.1f) < .9f - darken.channel(.9f))
        for (sample in 0..255) {
            val x = sample / 255f
            assertEquals(1f - darken.channel(1f - x), lift.channel(x), .000001f)
        }
    }
}

class GlassToneMixTest {
    @Test fun endpointsAreExactAndTheMixIsBetweenThem() {
        val dark = GlassTone(.25f, 2f, false); val light = GlassTone(.25f, 2f, true)
        for (sample in 0..255) {
            val x = sample / 255f
            assertEquals(dark.channel(x), GlassTone.mixed(.25f, 2f, 0f).channel(x), 0f)
            assertEquals(light.channel(x), GlassTone.mixed(.25f, 2f, 1f).channel(x), 0f)
            val half = GlassTone.mixed(.25f, 2f, .5f).channel(x)
            assertEquals((dark.channel(x) + light.channel(x)) / 2f, half, 1e-6f)
        }
    }

    @Test fun everyChannelValueMovesMonotonicallyAsThePolarityEases() {
        for (sample in 0..16) {
            val x = sample / 16f
            var last = GlassTone.mixed(.5f, 1.5f, 1f).channel(x)
            for (step in 1..100) {
                val tone = GlassTone.mixed(.5f, 1.5f, 1f - step / 100f)
                val value = tone.channel(x)
                assertTrue("$x at $step", value >= last - 1e-6f)
                last = value
            }
        }
    }

    @Test fun mixedRoundTripsAndEndpointPinsPolarity() {
        for (m in listOf(0f, .2f, .5f, .8f, 1f)) assertEquals(m, GlassTone.mixed(.1f, 2f, m).lightMix, 1e-6f)
        val halfway = GlassTone.mixed(.3f, 2f, .35f)
        assertEquals(0f, halfway.endpoint(false).lightMix, 0f); assertEquals(1f, halfway.endpoint(true).lightMix, 0f)
        assertEquals(GlassTone(.3f, 2f, true), halfway.endpoint(true))
    }
}
