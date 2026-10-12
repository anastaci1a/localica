package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassMomentumTest {
    @Test fun shapeConservesVolumeAndRespondsToStoppingWithoutPositionOvershoot() {
        val body = GlassMomentum()
        var maximum = 0f
        repeat(24) {
            body.advance(3f, 1f / 60)
            maximum = maxOf(maximum, body.strain)
            assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
        }
        assertTrue("Acceleration stretches the body", maximum > .005f)
        var minimum = 0f
        repeat(150) {
            body.advance(0f, 1f / 60)
            minimum = minOf(minimum, body.strain)
            assertTrue(abs(body.strain) <= .12f)
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
            assertTrue(abs(body.strain) <= .12f)
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
        assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
        repeat(120) { body.advance(0f, 1f / 60) }
        assertTrue(body.settled)
    }

    @Test fun contactFlattensDepthAndLowDampingReboundsWithoutLosingVolume() {
        for (damping in listOf(2f, 8f, 40f)) {
            val body = GlassMomentum(damping)
            repeat(180) { body.advance(0f, 1f / 60, contact = 1f) }
            assertTrue(body.shape.thickness < 1f)
            assertTrue(body.scaleX > 1f && body.scaleY > 1f)
            repeat(720) {
                body.advance(0f, 1f / 60)
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
            }
            assertTrue("All supported damping values sleep after settling", body.settled)
        }
    }

    @Test fun deformationPinsEitherContactingEdgeIncludingRebound() {
        for (extent in listOf(28f, 52f, 180f)) for (scale in listOf(.75f, 1f, 1.12f)) {
            for (wall in listOf(-1f, 1f)) {
                val movedCenter = extent / 2 + glassWallTranslation(extent, scale, wall)
                assertEquals(if (wall < 0) 0f else extent, movedCenter + wall * extent * scale / 2, .0001f)
            }
        }
    }

    @Test fun invalidInputAndLiveDampingChangesKeepTheBodyFiniteAndContinuous() {
        val body = GlassMomentum(2f)
        repeat(10) { body.advance(3f, 1f / 60, contact = 1f) }
        val previous = body.shape
        body.damping = 40f
        assertEquals(previous, body.shape)
        body.advance(Float.NaN, .5f, Float.NaN, Float.NaN)
        assertTrue(body.scaleX.isFinite() && body.scaleY.isFinite() && body.shape.thickness.isFinite())
    }
}
