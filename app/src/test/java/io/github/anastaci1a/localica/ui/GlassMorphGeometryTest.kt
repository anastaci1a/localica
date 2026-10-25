package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class GlassMorphGeometryTest {
    @Test fun quietShapeStaysAtItsHomeThroughoutTheMaterialFade() {
        for (p in listOf(0f, .035f, .07f, .105f, .14f)) {
            assertEquals("Bounds and corners must both be home before the fade", 0f,
                glassMorphGeometryProgress(p, quiet = true), 0f)
        }
    }

    @Test fun quietShapeUsesOneContinuousReversibleInterval() {
        val samples = (0..100).map { glassMorphGeometryProgress(it / 100f, quiet = true) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a && b - a < .012f) }
        assertEquals(.5f, glassMorphGeometryProgress(.57f, quiet = true), .000001f)
        assertEquals(samples, (100 downTo 0).map { glassMorphGeometryProgress(it / 100f, true) }.reversed())
    }

    @Test fun glassControlsKeepTheirExistingGeometryAndOvershootsStayBounded() {
        for (p in listOf(0f, .14f, .3f, .5f, .8f, 1f))
            assertEquals(p, glassMorphGeometryProgress(p, quiet = false), 0f)
        for (quiet in listOf(false, true)) {
            assertEquals(0f, glassMorphGeometryProgress(-.1f, quiet), 0f)
            assertEquals(1f, glassMorphGeometryProgress(1.1f, quiet), 0f)
        }
    }
}
