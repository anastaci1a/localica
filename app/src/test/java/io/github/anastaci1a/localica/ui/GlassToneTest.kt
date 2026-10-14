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
