package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassMomentumTest {
    @Test fun shapeConservesAreaAndRespondsToStoppingWithoutPositionOvershoot() {
        val body = GlassMomentum()
        var maximum = 0f
        repeat(24) {
            body.advance(3f, 1f / 60)
            maximum = maxOf(maximum, body.strain)
            assertEquals(1f, body.scaleX * body.scaleY, .00001f)
        }
        assertTrue("Acceleration stretches the body", maximum > .005f)
        var minimum = 0f
        repeat(90) {
            body.advance(0f, 1f / 60)
            minimum = minOf(minimum, body.strain)
            assertTrue(abs(body.strain) <= .065f)
        }
        assertTrue("Stopping compresses it in the opposite direction", minimum < -.005f)
        assertTrue(body.settled)
    }

    @Test fun interruptedTravelIsBoundedAndDissipatesAtDifferentFrameRates() {
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            repeat(fps / 3) { body.advance(4f, 1f / fps) }
            repeat(fps / 3) { body.advance(-4f, 1f / fps) }
            // A resumed/dropped frame must never explode the elastic integration.
            body.advance(50f, .5f)
            assertTrue(abs(body.strain) <= .065f)
            repeat(fps * 2) { body.advance(0f, 1f / fps) }
            assertTrue("Settles at $fps fps", body.settled)
        }
    }

    @Test fun edgePressureHasDiminishingReturnsAndReleaseSettles() {
        assertEquals(0f, glassEdgePressure(0f), .00001f)
        assertTrue(glassEdgePressure(1f) < glassEdgePressure(.5f))
        assertTrue(abs(glassEdgePressure(2f) - glassEdgePressure(1.5f)) < abs(glassEdgePressure(.5f)))
        assertTrue(glassEdgePressure(100f) >= -.22f)
        val body = GlassMomentum()
        repeat(120) { body.advance(0f, 1f / 60, glassEdgePressure(1f)) }
        assertTrue("A held drag keeps the lens compressed", body.strain < -.15f)
        assertEquals(1f, body.scaleX * body.scaleY, .00001f)
        repeat(120) { body.advance(0f, 1f / 60) }
        assertTrue(body.settled)
    }
}
