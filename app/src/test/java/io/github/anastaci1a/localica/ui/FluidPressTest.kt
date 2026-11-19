package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.anastaci1a.localica.ui.engine.SpringOracle
import io.github.anastaci1a.localica.ui.spec.FlexPress
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FluidPressTest {
    private fun channel(body: GlassMomentum, name: String, field: String): Float {
        val spring = body.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(body)
        return spring.javaClass.getDeclaredField(field).apply { isAccessible = true }.getFloat(spring)
    }
    private fun depth(body: GlassMomentum, field: String = "value") = channel(body, "lift", field)
    private fun oracle(x: Double, v: Double, target: Double, response: Double, damping: Double, t: Double) =
        SpringOracle.at(x, v, target, 2 * PI / response, damping, t)

    @Test fun explicitLiftAndReleaseMatchTheAnalyticalImpulseResponse() {
        for (large in listOf(false, true)) for (t in listOf(.008, .05, .2, .6)) {
            val body = GlassMomentum().also { if (large) it.spec = FlexSpec.dynamic(180f, 64f) }
            val r = if (large) .25 else .27; val z = if (large) 1.0 else .625
            val j = if (large) 0.0 else .032
            body.advance(0f, 0.0, contact = 1f)
            val target = 1.0
            val impulse = ((2 * PI / r).pow(2) * j * target).toFloat().toDouble()
            assertEquals(impulse, depth(body, "velocity").toDouble(), 1e-7)
            body.advance(0f, t, contact = 1f)
            val expected = oracle(0.0, impulse, target, r, z, t)
            assertEquals(expected.first, depth(body).toDouble(), 2e-7)
            assertEquals(expected.second, depth(body, "velocity").toDouble(), 2e-6)
            body.advance(0f, .8, contact = 1f)
            val x = depth(body).toDouble(); val v = depth(body, "velocity").toDouble()
            body.advance(0f, 0.0)
            val rr = if (large) .25 else .5; val zz = if (large) 1.0 else .7
            val vv = (v + (2 * PI / rr).pow(2) * (if (large) 0.0 else .016) * -target).toFloat().toDouble()
            assertEquals(vv, depth(body, "velocity").toDouble(), 1e-7)
            body.advance(0f, t)
            val released = oracle(x, vv, 0.0, rr, zz, t)
            assertEquals(released.first, depth(body).toDouble(), 2e-7)
            assertEquals(released.second, depth(body, "velocity").toDouble(), 2e-6)
        }
        val spec = FlexSpec.Small.lift
        assertEquals(.27, spec.response, 0.0); assertEquals(.625, spec.damping, 0.0)
        assertEquals(.032, spec.impulse, 0.0); assertEquals(.5, spec.releaseResponse, 0.0)
        assertEquals(.7, spec.releaseDamping, 0.0); assertEquals(.016, spec.releaseImpulse, 0.0)
        assertEquals(FlexPress.Large, FlexSpec.dynamic(180f).lift)
        val resized = GlassMomentum()
        resized.advance(0f, 0.0, contact = 1f)
        val v = depth(resized, "velocity").toDouble()
        resized.spec = FlexSpec.dynamic(180f, 64f)
        resized.advance(0f, .02, contact = 1f)
        val old = oracle(0.0, v, 1.0, .27, .625, .02)
        assertEquals(old.first, depth(resized).toDouble(), 2e-7)
        val next = oracle(depth(resized).toDouble(), depth(resized, "velocity").toDouble(), 1.0, .25, 1.0, .02)
        resized.advance(0f, .02, contact = 1f)
        assertEquals(next.first, depth(resized).toDouble(), 2e-7)
    }

    @Test fun hangSplitsThePreviousIntervalAtItsDeadlineAndEqualWritesDoNotRestartIt() {
        assertEquals(.22, FlexSpec.Small.lift.hang, 0.0)
        assertEquals(0.0, FlexPress.None.hang, 0.0)
        val whole = GlassMomentum(); val split = GlassMomentum()
        for (body in listOf(whole, split)) {
            body.advance(0f, 0.0, contact = 1f)
            body.advance(0f, .04, contact = 1f)
            body.advance(0f, 0.0)
            repeat(5) { body.advance(0f, 0.0) }
            assertEquals(1f, depth(body, "target"), 0f)
            assertFalse(body.finishSettlement())
        }
        whole.advance(0f, .2)
        split.advance(0f, .1)
        assertEquals(1f, depth(split, "target"), 0f)
        split.advance(0f, .1)
        for (field in listOf("value", "velocity", "target")) assertEquals(depth(whole, field), depth(split, field), 2e-6f)
        assertEquals(0f, depth(whole, "target"), 0f)
    }

    @Test fun repressCancelsPendingHangWithoutResettingPositionAndTransferRetainsTheDeadline() {
        val body = GlassMomentum()
        body.advance(0f, 0.0, contact = 1f); body.advance(0f, .03, contact = 1f); body.advance(0f, 0.0)
        val copy = GlassMomentum().apply { blendFrom(body) }
        for (item in listOf(body, copy)) {
            val x = depth(item); val v = depth(item, "velocity")
            item.advance(0f, 0.0, contact = 1f)
            assertEquals(x, depth(item), 0f); assertEquals(v, depth(item, "velocity"), 0f)
            item.advance(0f, .3, contact = 1f)
            assertEquals(1f, depth(item, "target"), 0f)
        }
        assertEquals(body.shape, copy.shape)
        body.reset(); assertEquals(GlassShape(), body.shape); assertTrue(body.settled)
    }

    @Test fun conditionalBodyKeepsItsTwoWobbleFrequenciesAndZeroImpulse() {
        val spec = FlexSpec.Small.copy(scaleGain = -(16f / 64f) / .24f)
        assertEquals(2.97948, sqrt(1.0 - spec.trackingDamping.toDouble().pow(2)) / spec.trackingResponse, .00001)
        assertEquals(2.31756, sqrt(1.0 - spec.damping.toDouble().pow(2)) / spec.response, .00001)
        for (t in listOf(.01, .1, .167814305, .4)) {
            val body = GlassMomentum(spec)
            assertEquals(FlexPress.None, body.bodyOverride!!.lift)
            body.advance(0f, 0.0, contact = 1f)
            assertEquals(0f, channel(body, "depth", "velocity"), 0f)
            body.advance(0f, t, contact = 1f)
            val expected = oracle(0.0, 0.0, (-.24f).toDouble(), spec.trackingResponse.toDouble(), spec.trackingDamping.toDouble(), t)
            assertEquals(expected.first, channel(body, "depth", "value").toDouble(), 2e-7)
            val x = channel(body, "depth", "value").toDouble(); val v = channel(body, "depth", "velocity").toDouble()
            body.advance(0f, 0.0)
            assertEquals(0f, channel(body, "depth", "target"), 0f)
            body.advance(0f, .1)
            assertEquals(oracle(x, v, 0.0, spec.response.toDouble(), spec.damping.toDouble(), .1).first, channel(body, "depth", "value").toDouble(), 2e-7)
        }
    }

    @Test fun heldFingerStrainIsSymmetricAndReturnsThroughTheExistingPressureSpring() {
        assertEquals(0f, glassFingerStrain(Offset(.5f, .5f)), 0f)
        assertEquals(.05f, glassFingerStrain(Offset(1f, .5f)), 0f)
        assertEquals(-.05f, glassFingerStrain(Offset(.5f, 0f)), 0f)
        for (x in listOf(0f, .2f, .5f, 1f)) for (y in listOf(0f, .3f, 1f)) {
            assertEquals(glassFingerStrain(Offset(x, y)), glassFingerStrain(Offset(1 - x, 1 - y)), 1e-7f)
            assertEquals(glassFingerStrain(Offset(x, y)), -glassFingerStrain(Offset(y, x)), 1e-7f)
        }
        val body = GlassMomentum()
        body.advance(0f, 0.0, contact = 1f, point = Offset(1f, .5f))
        body.advance(0f, .1, contact = 1f, point = Offset(1f, .5f))
        assertTrue(body.strain > 0f)
        val held = channel(body, "pressure", "value")
        body.advance(0f, 0.0)
        assertEquals(held, channel(body, "pressure", "value"), 0f)
        assertEquals(0f, channel(body, "pressure", "target"), 0f)
        repeat(600) { body.advance(0f, 1.0 / 120) }
        assertTrue(body.finishSettlement()); assertEquals(GlassShape(), body.shape)
    }

    @Test fun driftUsesOnlyOwnerLiftAndAnchorsNeverTranslate() {
        for (density in listOf(2f, 2.48125f, 3f)) {
            val size = Size(64 * density, 64 * density)
            val point = Offset(.8f, .2f)
            val drift = glassContactDrift(point, size, Offset(1.25f, .95f))
            assertEquals(4.8f * density, drift.x, 1e-5f)
            assertEquals(-4.8f * density, drift.y, 1e-5f)
            val rest = glassContactDrift(point, size, Offset(1f, 1f))
            assertEquals(0f, rest.x, 0f); assertEquals(0f, rest.y, 0f)
            assertEquals(Offset.Zero, glassContactDrift(Offset(.5f, .5f), size, Offset(1.25f, 1.25f)))
            val strainOnly = GlassShape(strain = .12f, depth = -.2f)
            val restLift = strainOnly.liftScale()
            val travelDrift = glassContactDrift(point, size, Offset(restLift, restLift))
            assertEquals(0f, travelDrift.x, 0f); assertEquals(0f, travelDrift.y, 0f)
            val lifted = strainOnly.copy(lift = ln(1.25f))
            assertEquals(1.25f, lifted.liftScale(), 0f)
            val anchorLift = lifted.liftScale(anchored = true)
            assertEquals(1f, anchorLift, 0f)
            val anchorDrift = glassContactDrift(point, Size(300 * density, 200 * density), Offset(anchorLift, anchorLift))
            assertEquals(0f, anchorDrift.x, 0f); assertEquals(0f, anchorDrift.y, 0f)

        }
    }
    @Test fun travelOnlyDepthKeepsTheOriginalSolverAndShapeDamping() {
        for (side in listOf(44f, 160f)) for (damping in listOf(2f, 8f, 40f)) {
            val body = GlassMomentum().apply { spec = FlexSpec.dynamic(side) }
            body.advance(0f, 0.0, damping = damping)
            body.advance(4f, .01, damping = damping)
            val x = channel(body, "depth", "value").toDouble()
            val v = channel(body, "depth", "velocity").toDouble()
            assertEquals(-.14f, v.toFloat(), 0f)
            val w = 2 * PI / body.spec.response
            val resistance = (2 * w * body.spec.damping * damping / 8).toFloat()
            val expected = SpringOracle.at(x, v, 0.0, w, resistance / (2 * w), .025)
            body.advance(4f, .025, damping = damping)
            assertEquals(expected.first, channel(body, "depth", "value").toDouble(), 2e-7)
            assertEquals(expected.second, channel(body, "depth", "velocity").toDouble(), 2e-6)
            assertEquals(0f, depth(body), 0f); assertEquals(0f, depth(body, "velocity"), 0f)
            assertEquals(0f, body.shape.lift, 0f)
        }
    }

    @Test fun governingBodyTransfersItsDeadlineAndFingerPressureKeepsOriginalBounds() {
        val a = GlassMomentum(); val b = GlassMomentum()
        a.advance(0f, 0.0, contact = 1f); a.advance(0f, .12, contact = 1f); a.advance(0f, 0.0)
        b.advance(0f, 0.0, contact = 1f); b.advance(0f, .22, contact = 1f)
        val age = a.javaClass.getDeclaredField("liftAge").apply { isAccessible = true }
        for (weight in listOf(0f, .25f, .75f, 1f)) {
            val joined = GlassMomentum().apply { blendFrom(a, b, weight) }
            assertEquals(age.getDouble(if (weight < .5f) a else b), age.getDouble(joined), 0.0)
        }
        for (edge in listOf(-.22f, .12f)) for (point in listOf(Offset(0f, .5f), Offset(.5f, 0f))) {
            val body = GlassMomentum()
            body.advance(0f, 0.0, pressure = edge, contact = 1f, point = point)
            assertEquals((edge + glassFingerStrain(point)).coerceIn(-.22f, .12f), channel(body, "pressure", "target"), 0f)
        }
    }

}
