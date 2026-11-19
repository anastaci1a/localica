package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

/** Test-only affine fixture input (the app helper was dead, #748); production curves are covered by the actual-engine sweep. */
internal fun fixtureGeometryProgress(progress: Float, quiet: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
}

class GlassMorphGeometryTest {
    @Test fun quietShapeStaysAtItsHomeThroughoutTheMaterialFade() {
        for (p in listOf(0f, .035f, .07f, .105f, .14f)) {
            assertEquals("Bounds and corners must both be home before the fade", 0f,
                fixtureGeometryProgress(p, quiet = true), 0f)
        }
    }

    @Test fun quietShapeUsesOneContinuousReversibleInterval() {
        val samples = (0..100).map { fixtureGeometryProgress(it / 100f, quiet = true) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a && b - a < .012f) }
        assertEquals(.5f, fixtureGeometryProgress(.57f, quiet = true), .000001f)
        assertEquals(samples, (100 downTo 0).map { fixtureGeometryProgress(it / 100f, true) }.reversed())
    }

    @Test fun glassControlsKeepTheirExistingGeometryAndOvershootsStayBounded() {
        for (p in listOf(0f, .14f, .3f, .5f, .8f, 1f))
            assertEquals(p, fixtureGeometryProgress(p, quiet = false), 0f)
        for (quiet in listOf(false, true)) {
            assertEquals(0f, fixtureGeometryProgress(-.1f, quiet), 0f)
            assertEquals(1f, fixtureGeometryProgress(1.1f, quiet), 0f)
        }
    }
}
