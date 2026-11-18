package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import kotlin.math.*

class GlassMomentumTest {
    @Test fun normalConvergencePublishesExactTargetsInsteadOfResidualLayoutRounding() {
        for (fps in listOf(30, 60, 120)) for (damping in listOf(2f, 8f, 40f)) {
            val body = GlassMomentum()
            repeat(fps) { body.advance(3f, (1f / fps).toDouble(), pressure = -.15f, contact = 1f, damping = damping) }
            var frames = 0
            do { body.advance(0f, (1f / fps).toDouble(), damping = damping); frames++ } while (!body.settled && frames < fps * 20)
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
        repeat(5) { for (item in listOf(body, uninterrupted)) item.advance(3f, (1f / 60).toDouble(), contact = 1f) }
        val shape = body.shape
        assertFalse(body.finishSettlement())
        assertEquals(shape, body.shape)
        repeat(120) {
            body.advance(-2f, (1f / 60).toDouble())
            uninterrupted.advance(-2f, (1f / 60).toDouble())
            assertEquals("Rejected completion preserves momentum as well as shape", uninterrupted.shape, body.shape)
        }
    }

    @Test fun heldConvergenceFinishesAtItsForcedTargetInsteadOfResettingToRest() {
        val body = GlassMomentum()
        var frames = 0
        do { body.advance(0f, (1f / 60).toDouble(), pressure = -.15f, contact = 1f); frames++ }
        while (!body.settled && frames < 1200)
        assertTrue(body.finishSettlement())
        assertEquals(-.15f, body.strain, 0f)
        assertTrue(body.shape.depth < 0f)
    }

    @Test fun axisTransferPreservesScreenShapeAndReleaseVelocity() {
        val horizontal = GlassMomentum()
        repeat(12) { horizontal.advance(3f, (1f / 120).toDouble()) }
        val vertical = GlassMomentum().apply { blendFrom(horizontal, aAxis = -1f, bAxis = -1f) }
        assertEquals(horizontal.shape.scaleX(), vertical.shape.scaleX(true), .000001f)
        assertEquals(horizontal.shape.scaleY(), vertical.shape.scaleY(true), .000001f)
        // Maintain constant travel speed so no new directional impulse is injected.
        repeat(120) {
            horizontal.advance(3f, (1f / 120).toDouble())
            vertical.advance(3f, (1f / 120).toDouble())
            assertEquals(horizontal.shape.scaleX(), vertical.shape.scaleX(true), .000001f)
            assertEquals(horizontal.shape.scaleY(), vertical.shape.scaleY(true), .000001f)
        }
    }

    @Test fun splittingRetainsReleaseEnergyAndSubsequentMotion() {
        for (fps in listOf(30, 60, 120)) for (damping in listOf(2f, 8f, 40f)) {
            val joined = GlassMomentum()
            repeat(fps / 4) { joined.advance(2f, (1f / fps).toDouble(), contact = 1f, damping = damping) }
            repeat(3) { joined.advance(0f, (1f / fps).toDouble(), damping = damping) }
            val split = GlassMomentum().apply { blendFrom(joined) }
            assertEquals(joined.shape, split.shape)
            joined.advance(0f, 0.0, damping = damping)
            split.advance(0f, 0.0, damping = damping)
            repeat(fps) {
                val velocity = if (it < fps / 3) -1f else 0f
                joined.advance(velocity, (1f / fps).toDouble(), damping = damping)
                split.advance(velocity, (1f / fps).toDouble(), damping = damping)
                assertEquals("Transfer retains velocity, not merely the current scale", joined.shape, split.shape)
            }
        }
    }

    @Test fun rejoiningIsIndependentOfWhichHalfOwnsTheMergedClock() {
        val left = GlassMomentum(); val right = GlassMomentum()
        repeat(20) { left.advance(2f, (1f / 120).toDouble(), contact = 1f); right.advance(-1f, (1f / 120).toDouble()) }
        val joined = GlassMomentum().apply { blendFrom(left, right, .5f) }
        val reversed = GlassMomentum().apply { blendFrom(right, left, .5f) }
        left.blendFrom(left, right, .5f)
        for (body in listOf(joined, reversed, left)) body.advance(0f, 0.0)
        repeat(240) {
            for (body in listOf(joined, reversed, left)) body.advance(0f, (1f / 120).toDouble())
            assertEquals(joined.shape.strain, reversed.shape.strain, .000001f)
            assertEquals(joined.shape.depth, reversed.shape.depth, .000001f)
            assertEquals("In-place adoption must retain the same state", joined.shape, left.shape)
        }
        assertTrue(joined.settled)
    }

    private fun rawMotion(body: GlassMomentum): Float {
        val spring = body.javaClass.getDeclaredField("motion").apply { isAccessible = true }.get(body)
        return spring.javaClass.getDeclaredField("value").apply { isAccessible = true }.getFloat(spring)
    }

    @Test fun heldAccelerationAndBrakingRemainVisibleWithBoundedTrackingRebound() {
        for (fps in listOf(30, 60, 120)) for (damping in listOf(2f, 8f, 40f))
            for (direction in listOf(-1f, 1f)) for (braking in listOf(false, true)) {
                val body = GlassMomentum()
                val initialVelocity = if (braking) direction * 4f else 0f
                repeat(fps * 3) { body.advance(initialVelocity, (1f / fps).toDouble(), contact = 1f, damping = damping) }
                val nextVelocity = if (braking) 0f else direction * 4f
                var peak = 0f; var trough = 0f
                var rawPeak = 0f; var rawTrough = 0f
                repeat(fps) {
                    body.advance(nextVelocity, (1f / fps).toDouble(), contact = 1f, damping = damping)
                    val raw = rawMotion(body)
                    assertEquals("Visible mapping stays exact at every peak/trough candidate", .12f * tanh(raw / .12f), body.strain, 0f)
                    val sign = if (braking) -1f else 1f
                    val rawAxial = raw * sign
                    rawTrough = minOf(rawTrough, rawAxial)
                    rawPeak = maxOf(rawPeak, rawAxial)
                    val axial = body.strain * sign
                    trough = minOf(trough, axial)
                    peak = maxOf(peak, axial)
                    assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
                }
                assertTrue("Held acceleration/braking retains a visible impulse", peak > if (damping == 40f) .0005f else .004f)
                assertTrue("Held impulse remains bounded", peak < .02f)
                if (damping == 8f) assertTrue("Default tracking rebound <= 8.1%", -rawTrough <= rawPeak * .081f)
                if (damping == 2f) assertTrue("Stored-2 tracking rebound <= 62%", -trough <= peak * .62f)
                if (damping == 40f) assertTrue("Stored-40 tracking does not cross rest", trough >= -.000001f)
                // The slowest tracking envelope decays at 3.127/s; 3 s keeps the original tolerance.
                repeat(fps * 2) { body.advance(nextVelocity, (1f / fps).toDouble(), contact = 1f, damping = damping) }
                assertEquals("Steady tracking dissipates the impulse", 0f, body.strain, .00001f)
            }
    }

    @Test fun alternatingHeldImpulsesRetargetContinuouslyAndSettle() {
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            var minimum = 0f; var maximum = 0f
            for (velocity in listOf(4f, 0f, 4f, 0f, 4f, 0f)) repeat(fps / 5) {
                body.advance(velocity, (1f / fps).toDouble(), contact = 1f)
                minimum = minOf(minimum, body.strain); maximum = maxOf(maximum, body.strain)
                assertTrue("Opposing impulses remain bounded", abs(body.strain) < .12f)
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
            }
            assertTrue("Acceleration stays responsive after braking", maximum > .004f)
            assertTrue("Braking can oppose a live acceleration response", minimum < -.004f)
            repeat(fps * 3) { body.advance(0f, (1f / fps).toDouble(), contact = 1f) }
            assertTrue("Stationary held input sleeps after forced changes", body.settled)
        }
    }

    @Test fun heldAccelerationIsSymmetricAndConvergesToTheStationaryContactDepth() {
        val forward = GlassMomentum(); val backward = GlassMomentum(); val stationary = GlassMomentum()
        repeat(180) { for (body in listOf(forward, backward, stationary)) body.advance(0f, (1f / 60).toDouble(), contact = 1f) }
        var depthImpulse = 0f
        repeat(60) {
            forward.advance(4f, (1f / 60).toDouble(), contact = 1f)
            backward.advance(-4f, (1f / 60).toDouble(), contact = 1f)
            stationary.advance(0f, (1f / 60).toDouble(), contact = 1f)
            assertEquals(forward.shape, backward.shape)
            depthImpulse = maxOf(depthImpulse, abs(forward.shape.depth - stationary.shape.depth))
            assertTrue("Acceleration adds bounded contact depth", abs(forward.shape.depth - stationary.shape.depth) < .002f)
            assertEquals(1f, forward.scaleX * forward.scaleY * forward.shape.thickness, .00001f)
        }
        assertTrue("Acceleration retains a nonzero depth impulse", depthImpulse > 0f)
        repeat(120) { forward.advance(4f, (1f / 60).toDouble(), contact = 1f) }
        assertEquals("Steady travel returns to the contact target", stationary.shape.depth, forward.shape.depth, .00001f)
    }

    @Test fun trackingPressAndFreeReleaseMatchS323BandsWithoutChangingVolume() {
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            body.advance(0f, 0.0, contact = 1f)
            var peak = 0f; var peakTime = 0f
            repeat(fps * 3) {
                body.advance(0f, (1f / fps).toDouble(), contact = 1f)
                val displacement = atanh(-body.shape.depth / .24f)
                if (displacement > peak) { peak = displacement; peakTime = (it + 1f) / fps }
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
            }
            assertTrue("Tracking overshoot <= 8.1% and inside S323 0..15%", peak > 1f && peak <= 1.081f)
            assertTrue("S323 growth peak 133..167 +/-33 ms: $peakTime", peakTime in .1f.. .2f)
            body.advance(0f, 0.0)
            var minimum = 0f; var minimumTime = 0f
            repeat(fps * 2) {
                body.advance(0f, (1f / fps).toDouble())
                val displacement = atanh(-body.shape.depth / .24f)
                if (displacement < minimum) { minimum = displacement; minimumTime = (it + 1f) / fps }
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
            }
            assertTrue("S323 release undershoot 26 +/-8%: $minimum", minimum in -.34f..-.18f)
            assertTrue("S323 release minimum 183 +/-33 ms: $minimumTime", minimumTime in .15f.. .2167f)
        }
    }

    @Test fun dynamicSpringsUseTheExtractedLongSideBoundaryAndInterpolation() {
        for (side in listOf(0f, 44f, 102f, 119.999f)) assertEquals(FlexSpec.Small, FlexSpec.dynamic(side))
        for (side in listOf(120f, 140f, 160f, 320f)) {
            val spec = FlexSpec.dynamic(side)
            val t = ((side - 44f) / 116f).coerceIn(0f, 1f)
            assertEquals(.375f * (1 - t) + .6f * t, spec.damping, .000001f)
            assertEquals(.4f * (1 - t) + .36f * t, spec.response, .000001f)
            assertEquals(.625f, spec.trackingDamping, 0f)
            assertEquals(.262f * (1 - t) + .314f * t, spec.trackingResponse, .000001f)
        }
    }

    @Test fun springStepsFollowIndependentClosedFormAcrossSizesAndDampingSettings() {
        for (side in listOf(44f, 120f, 140f, 160f)) for (damping in listOf(2f, 8f, 40f))
            for (fps in listOf(30, 60, 120)) for (held in listOf(false, true)) {
                val body = GlassMomentum().apply { spec = FlexSpec.dynamic(side) }
                // Pressure exposes a channel directly and its nonzero target selects tracking.
                repeat(fps * 8) { body.advance(0f, (1f / fps).toDouble(), pressure = -.1f, damping = damping) }
                body.finishSettlement()
                val omega = 2 * PI / if (held) body.spec.trackingResponse else body.spec.response
                val zeta = (if (held) body.spec.trackingDamping else body.spec.damping) * damping / 8
                fun response(t: Double): Double {
                    val a = zeta * omega
                    return when {
                        zeta < 1 -> { val b = omega * sqrt(1 - zeta * zeta)
                            exp(-a * t) * (cos(b * t) + a / b * sin(b * t)) }
                        zeta > 1 -> { val b = omega * sqrt(zeta * zeta - 1)
                            ((1 + a / b) * exp((-a + b) * t) + (1 - a / b) * exp((-a - b) * t)) / 2 }
                        else -> exp(-a * t) * (1 + a * t)
                    }
                }
                body.advance(0f, 0.0, pressure = if (held) -.2f else 0f, damping = damping)
                repeat(fps * 2) {
                    body.advance(0f, (1f / fps).toDouble(), pressure = if (held) -.2f else 0f, damping = damping)
                    val q = body.strain / -.1f
                    val expected = if (held) 2 - response((it + 1.0) / fps) else response((it + 1.0) / fps)
                    assertEquals("side=$side damping=$damping fps=$fps held=$held frame=$it", expected, q.toDouble(), .055)
                }
            }
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
            body.advance(3f, (1f / 60).toDouble())
            maximum = maxOf(maximum, body.strain)
            assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
        }
        assertTrue("Acceleration stretches the body", maximum > .005f)
        var minimum = 0f
        repeat(150) {
            body.advance(0f, (1f / 60).toDouble())
            minimum = minOf(minimum, body.strain)
            assertTrue(abs(body.strain) <= .12f)
        }
        assertTrue("Stopping compresses it in the opposite direction", minimum < -.005f)
        assertTrue(body.settled)
    }

    @Test fun interruptedTravelIsBoundedAndDissipatesAtDifferentFrameRates() {
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            repeat(fps / 3) { body.advance(4f, (1f / fps).toDouble()) }
            repeat(fps / 3) { body.advance(-4f, (1f / fps).toDouble()) }
            // A resumed/dropped frame must never explode the elastic integration.
            body.advance(50f, (.5f).toDouble())
            assertTrue(abs(body.strain) <= .12f)
            repeat(fps * 2) { body.advance(0f, (1f / fps).toDouble()) }
            assertTrue("Settles at $fps fps", body.settled)
        }
    }

    @Test fun edgePressureHasDiminishingReturnsAndReleaseSettles() {
        assertEquals(0f, glassEdgePressure(0f), .00001f)
        assertTrue(glassEdgePressure(1f) < glassEdgePressure(.5f))
        assertTrue(abs(glassEdgePressure(2f) - glassEdgePressure(1.5f)) < abs(glassEdgePressure(.5f)))
        assertTrue(glassEdgePressure(100f) >= -.22f)
        val body = GlassMomentum()
        repeat(120) { body.advance(0f, (1f / 60).toDouble(), glassEdgePressure(1f)) }
        assertTrue("A held drag keeps the lens compressed", body.strain < -.15f)
        assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
        repeat(120) { body.advance(0f, (1f / 60).toDouble()) }
        assertTrue(body.settled)
    }

    @Test fun contactFlattensDepthAndLowDampingReboundsWithoutLosingVolume() {
        for (damping in listOf(2f, 8f, 40f)) {
            val body = GlassMomentum()
            repeat(180) { body.advance(0f, (1f / 60).toDouble(), contact = 1f, damping = damping) }
            assertTrue(body.shape.thickness < 1f)
            assertTrue(body.scaleX > 1f && body.scaleY > 1f)
            repeat(720) {
                body.advance(0f, (1f / 60).toDouble(), damping = damping)
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
        val body = GlassMomentum()
        repeat(10) { body.advance(3f, (1f / 60).toDouble(), contact = 1f, damping = 2f) }
        val previous = body.shape
        body.advance(0f, 0.0, contact = 1f, damping = 40f)
        assertEquals(previous, body.shape)
        body.advance(Float.NaN, (.5f).toDouble(), Float.NaN, Float.NaN, damping = 40f)
        assertTrue(body.scaleX.isFinite() && body.scaleY.isFinite() && body.shape.thickness.isFinite())
    }
}
