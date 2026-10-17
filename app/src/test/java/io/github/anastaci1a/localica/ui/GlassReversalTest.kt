package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassReversalTest {
    @Test fun ordinaryBrakingAndAccelerationRetainTheirMagnitudeImpulse() {
        for (direction in listOf(-1f, 1f)) for ((before, after) in listOf(
            0f to 4f, 4f to 0f, 4f to 8f, 8f to 4f, 4f to 4f)) {
            assertEquals(abs(after) - abs(before), glassMotionSpeedChange(before * direction, after * direction), 0f)
        }
        assertEquals(-8f, glassMotionSpeedChange(4f, -4f), 0f)
        assertEquals(-8f, glassMotionSpeedChange(-4f, 4f), 0f)
    }

    @Test fun abruptHeldReversalIsSymmetricBoundedAndFrameRateStable() {
        val peaks = mutableListOf<Float>()
        for (fps in listOf(30, 60, 120)) {
            val forward = GlassMomentum(); val backward = GlassMomentum()
            repeat(fps * 2) {
                forward.advance(4f, 1f / fps, contact = 1f)
                backward.advance(-4f, 1f / fps, contact = 1f)
            }
            var minimum = 0f
            repeat(fps) {
                forward.advance(-4f, 1f / fps, contact = 1f)
                backward.advance(4f, 1f / fps, contact = 1f)
                assertEquals(forward.shape, backward.shape)
                minimum = minOf(minimum, forward.strain)
                assertTrue(abs(forward.strain) <= .12f)
                assertEquals(1f, forward.scaleX * forward.scaleY * forward.shape.thickness, .00001f)
            }
            assertTrue("Reversal cannot look like unchanged speed", minimum < -.008f)
            assertEquals(0f, forward.strain, .00001f)
            peaks += minimum
        }
        assertTrue("Sampling the same step at30–120fps preserves its strength", peaks.max() - peaks.min() < .001f)
    }

    @Test fun unequalReversalsAndImpulseClampsStayMirroredAndSettle() {
        assertEquals(1f, glassMotionSpeedChange(1f, -4f), 0f)
        assertEquals(-5f, glassMotionSpeedChange(4f, -1f), 0f)
        for (contact in listOf(0f, 1f)) {
            val forward = GlassMomentum(); val backward = GlassMomentum()
            for (speed in listOf(1f, -4f, 4f, -1f, 30f, -30f, 1000f, -1000f, 0f)) {
                repeat(6) {
                    forward.advance(speed, 1f / 60, contact = contact)
                    backward.advance(-speed, 1f / 60, contact = contact)
                    assertEquals(forward.shape, backward.shape)
                    assertTrue("Per-channel impulses retain their bounds", abs(forward.strain) <= .12f)
                    assertTrue(forward.shape.depth.isFinite())
                    assertEquals(1f, forward.scaleX * forward.scaleY * forward.shape.thickness, .00001f)
                }
            }
            repeat(240) { forward.advance(0f, 1f / 60, contact = contact) }
            assertTrue("Reversals settle whether contact continues or ends", forward.settled)
        }
    }

    @Test fun slowOffGridZeroCrossingAddsOnlyAVanishingBoundedCorrection() {
        for (fps in listOf(30, 60, 120)) for (direction in listOf(-1f, 1f)) {
            val body = GlassMomentum()
            var previous = 4f * direction
            repeat(fps * 2) { body.advance(previous, 1f / fps, contact = 1f) }
            repeat(fps) { frame ->
                //370ms deliberately avoids a zero exactly on every sample grid.
                val speed = direction * (4f - 8f * (frame + 1f) / fps / .37f).coerceAtLeast(-4f)
                val correction = glassMotionSpeedChange(previous, speed) - (abs(speed) - abs(previous))
                assertTrue("Crossing correction shrinks with per-frame speed change", abs(correction) <= 8f / .37f / fps + .00001f)
                if (previous * speed >= 0f) assertEquals(0f, correction, 0f)
                body.advance(speed, 1f / fps, contact = 1f)
                assertTrue("Slow reversal cannot create an abrupt-impact-sized impulse", abs(body.strain) < .007f)
                previous = speed
            }
        }
        assertEquals(0f, glassMotionSpeedChange(.000001f, -.000001f), .000003f)
    }
}
