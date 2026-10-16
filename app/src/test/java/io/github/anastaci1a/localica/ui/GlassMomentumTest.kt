package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassMomentumTest {
    @Test fun normalConvergencePublishesExactTargetsInsteadOfResidualLayoutRounding() {
        for (fps in listOf(30, 60, 120)) for (damping in listOf(2f, 8f, 40f)) {
            val body = GlassMomentum(damping)
            repeat(fps) { body.advance(3f, 1f / fps, pressure = -.15f, contact = 1f) }
            var frames = 0
            do { body.advance(0f, 1f / fps); frames++ } while (!body.settled && frames < fps * 20)
            assertTrue("Normal return converges at $fps fps, damping=$damping", body.settled)
            assertTrue("The convergence epsilon leaves a real residual", body.shape.strain != 0f || body.shape.depth != 0f)
            assertTrue(body.finishSettlement())
            assertEquals(GlassShape(), body.shape)
            assertEquals(1f, body.scaleX, 0f)
            assertEquals(1f, body.scaleY, 0f)
        }
    }

    @Test fun unfinishedSettlementPreservesTheShapeAndVelocityForTheNextOwner() {
        val body = GlassMomentum(); val uninterrupted = GlassMomentum()
        repeat(5) { for (item in listOf(body, uninterrupted)) item.advance(3f, 1f / 60, contact = 1f) }
        val shape = body.shape
        assertFalse(body.finishSettlement())
        assertEquals(shape, body.shape)
        repeat(120) {
            body.advance(-2f, 1f / 60)
            uninterrupted.advance(-2f, 1f / 60)
            assertEquals("Rejected completion preserves momentum as well as shape", uninterrupted.shape, body.shape)
        }
    }

    @Test fun heldConvergenceFinishesAtItsForcedTargetInsteadOfResettingToRest() {
        val body = GlassMomentum()
        var frames = 0
        do { body.advance(0f, 1f / 60, pressure = -.15f, contact = 1f); frames++ }
        while (!body.settled && frames < 1200)
        assertTrue(body.finishSettlement())
        assertEquals(-.15f, body.strain, 0f)
        assertTrue(body.shape.depth < 0f)
    }

    @Test fun heldAccelerationAndBrakingRemainVisibleWithoutRinging() {
        for (fps in listOf(30, 60, 120)) for (damping in listOf(2f, 8f, 40f))
            for (direction in listOf(-1f, 1f)) for (braking in listOf(false, true)) {
                val body = GlassMomentum(damping)
                val initialVelocity = if (braking) direction * 4f else 0f
                repeat(fps * 3) { body.advance(initialVelocity, 1f / fps, contact = 1f) }
                val nextVelocity = if (braking) 0f else direction * 4f
                var peak = 0f
                repeat(fps) {
                    body.advance(nextVelocity, 1f / fps, contact = 1f)
                    val axial = body.strain * if (braking) -1f else 1f
                    assertTrue("Held impulse cannot ring across rest at $fps fps", axial >= -.000001f)
                    peak = maxOf(peak, axial)
                    assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
                }
                assertTrue("Held acceleration/braking must survive target projection", peak > .004f)
                assertTrue("Held impulse remains bounded", peak < .02f)
                assertEquals("Steady tracking dissipates the impulse", 0f, body.strain, .00001f)
            }
    }

    @Test fun alternatingHeldImpulsesRetargetContinuouslyAndSettle() {
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            var minimum = 0f; var maximum = 0f
            for (velocity in listOf(4f, 0f, 4f, 0f, 4f, 0f)) repeat(fps / 5) {
                body.advance(velocity, 1f / fps, contact = 1f)
                minimum = minOf(minimum, body.strain); maximum = maxOf(maximum, body.strain)
                assertTrue("Opposing impulses remain bounded", abs(body.strain) < .12f)
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
            }
            assertTrue("Acceleration stays responsive after braking", maximum > .004f)
            assertTrue("Braking can oppose a live acceleration response", minimum < -.004f)
            repeat(fps * 3) { body.advance(0f, 1f / fps, contact = 1f) }
            assertTrue("Stationary held input sleeps after forced changes", body.settled)
        }
    }

    @Test fun heldAccelerationIsSymmetricAndDoesNotChangeStationaryContactDepth() {
        val forward = GlassMomentum(); val backward = GlassMomentum(); val stationary = GlassMomentum()
        repeat(180) { for (body in listOf(forward, backward, stationary)) body.advance(0f, 1f / 60, contact = 1f) }
        repeat(60) {
            forward.advance(4f, 1f / 60, contact = 1f)
            backward.advance(-4f, 1f / 60, contact = 1f)
            stationary.advance(0f, 1f / 60, contact = 1f)
            assertEquals(forward.shape, backward.shape)
            assertEquals("Pressure/contact depth constraints stay independent", stationary.shape.depth, forward.shape.depth, .00001f)
        }
    }

    @Test fun pressingStoresStrainWithoutAnyBounceAndReleaseOscillatesDepth() {
        val body = GlassMomentum(2f)
        var previous = 1f
        repeat(180) {
            body.advance(0f, 1f / 120, contact = 1f)
            assertTrue("Contact must not rebound under the finger", body.shape.thickness <= previous + .000001f)
            previous = body.shape.thickness
        }
        var roseAboveRest = false
        repeat(120) {
            body.advance(0f, 1f / 120)
            roseAboveRest = roseAboveRest || body.shape.thickness > 1.01f
        }
        assertTrue("Release returns stored strain through depth", roseAboveRest)
    }

    @Test fun magneticWellsAreContinuousOrderedAndStrongNearEachOption() {
        var previous = magneticChoicePosition(-2f)
        for (i in -1999..5000) {
            val raw = i / 1000f
            val next = magneticChoicePosition(raw)
            assertTrue(next >= previous)
            assertTrue(next - previous < .004f)
            previous = next
        }
        assertTrue(magneticChoicePosition(.2f) < .05f)
        assertEquals(.5f, magneticChoicePosition(.5f), .00001f)
    }
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
