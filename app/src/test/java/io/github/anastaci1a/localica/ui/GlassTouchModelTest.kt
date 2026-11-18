package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlassTouchModelTest {
    private fun records(body: GlassMomentum) = body.javaClass.getDeclaredField("locals").apply { isAccessible = true }.get(body) as List<*>
    private fun current(body: GlassMomentum) = records(body).last()!!
    private fun light(body: GlassMomentum, index: Int, field: String): Float {
        val channels = current(body).javaClass.getDeclaredField("light").apply { isAccessible = true }.get(current(body)) as List<*>
        val spring = if (index == 0) body.javaClass.getDeclaredField("broad").apply { isAccessible = true }.get(body) else channels[index - 1]
        return spring!!.javaClass.getDeclaredField(field).apply { isAccessible = true }.getFloat(spring)
    }
    private fun evolve(x: Double, v: Double, target: Double, dt: Double, response: Double, damping: Double): Pair<Double, Double> {
        val w = 2 * PI / response
        val a = damping * w
        val y = x - target
        if (damping == 1.0) {
            val b = v + w * y; val e = exp(-w * dt)
            return target + (y + b * dt) * e to (v - w * b * dt) * e
        }
        val f = w * sqrt(1 - damping * damping)
        val b = (v + a * y) / f
        val e = exp(-a * dt); val c = cos(f * dt); val s = sin(f * dt)
        return target + e * (y * c + b * s) to e * (v * c - (a * b + f * y) * s)
    }

    @Test fun radiusAndIndependentOpacityTargetsUseOneDensityConversion() {
        for (density in listOf(2f, 2.48125f, 3f)) for (short in listOf(1f, 44f, 80f, 102f, 160f, 400f)) {
            assertEquals((min(.75 * short, 80.0) * density).toFloat(), FlexGlow.radius(short * density, density), .00002f)
            val u = ((short.toDouble() - 44) / 116).coerceIn(0.0, 1.0)
            val spec = FlexSpec.dynamic(240f, short)
            assertEquals(Math.fma(1 - u, 1.0, u * 0.0).toFloat().toBits(), spec.bigOpacity.toBits())
            assertEquals(Math.fma(1 - u, .3, u * .2).toFloat().toBits(), spec.littleOpacity.toBits())
        }
        assertEquals(1f, FlexSpec.dynamic(119.999f, 44f).littleOpacity, 0f)
    }

    @Test fun everyFloatSizeKeepsTheFusedOpacityBitsOnEveryApi() {
        for (bits in 44f.toBits()..160f.toBits()) {
            val short = Float.fromBits(bits)
            val u = (short.toDouble() - 44) / 116
            val spec = FlexSpec.dynamic(240f, short)
            val expected = Math.fma(1 - u, .3, u * .2).toFloat()
            if (spec.littleOpacity.toBits() != expected.toBits())
                fail("short=$short expected=$expected actual=" + spec.littleOpacity)
            assertEquals((1 - u).toFloat().toBits(), spec.bigOpacity.toBits())
        }
        for (index in 0..65536) {
            val luminance = index / 65536f
            val p = Math.pow(luminance.toDouble(), 1.25)
            assertEquals(Math.fma(1 - p, .9, p * .25).toFloat().toBits(), glowWhite(luminance).toBits())
        }
    }

    @Test fun smoothedTrackingFollowsTheParameterThenPreservesRetargetVelocity() {
        for (density in listOf(2f, 2.48125f, 3f)) for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            body.targetGlow(Offset(.1f, .5f), 1f, 1f)
            var x = .1f.toDouble(); var v = 0.0
            val dt = 1.0 / fps
            repeat(fps * 2) { frame ->
                val target = if (frame < fps / 3) 1.0 else .2f.toDouble()
                body.targetGlow(Offset(target.toFloat(), .5f), 1f, 1f)
                val oldVelocity = light(body, 2, "velocity")
                body.targetGlow(Offset(target.toFloat(), .5f), 1f, 1f)
                assertEquals(oldVelocity, light(body, 2, "velocity"), 0f)
                body.advance(0f, dt, contact = 1f)
                val t = (frame + 1) * dt
                val w = 2 * PI
                val response = .0015f + (.15f.toDouble() - .0015f) * (1 + w * t) * exp(-w * t)
                assertEquals(response, light(body, 4, "value").toDouble(), .0000001)
                val next = evolve(x, v, target, dt, response, 1.0)
                x = next.first.toFloat().toDouble(); v = next.second.toFloat().toDouble()
                assertEquals("density=$density fps=$fps frame=$frame", x * density, body.glow.point.x * density.toDouble(), .00003)
            }
            body.targetGlow(Offset(1.25f, -.25f), 1f, 1f, snap = true)
            assertEquals(Offset(1f, 0f), body.glow.point)
            body.targetGlow(Offset(.8f, .2f), 0f, 1f)
            body.advance(0f, .01)
            val alpha = light(body, 1, "value"); val energy = light(body, 1, "velocity")
            body.targetGlow(Offset(.8f, .2f), 1f, 1f)
            assertEquals(0f, light(body, 1, "value"), 0f)
            assertEquals(0f, light(body, 1, "velocity"), 0f)
            assertEquals(alpha, body.glow.retiring.single().little, 0f)
            val retired = records(body).first()!!
            val oldAlpha = (retired.javaClass.getDeclaredField("light").apply { isAccessible = true }.get(retired) as List<*>).first()!!
            assertEquals(energy, oldAlpha.javaClass.getDeclaredField("velocity").apply { isAccessible = true }.getFloat(oldAlpha), 0f)
            assertEquals(.15f, light(body, 4, "value"), 0f)
        }
    }

    @Test fun diskMaskHasBlurredSourceSupportAndRecoveredColourCoefficients() {
        val disk = glowDisk(8f); val side = glowDiskSide(8f); val center = side / 2
        fun alpha(dx: Int) = disk[center * side + center + dx] ushr 24
        assertEquals(102, alpha(0))
        assertTrue(alpha(8) > 0)
        assertEquals(0, alpha(28))
        val broad = glowMatrix(false, 0f)
        val local = glowMatrix(true, 0f)
        assertEquals(1.1575000286102295f.toBits(), broad[0].toBits())
        assertEquals(1.056999921798706f.toBits(), broad[6].toBits())
        assertEquals(1.18559992313385f.toBits(), broad[12].toBits())
        assertEquals(1.3937000036239624f.toBits(), local[0].toBits())
        assertEquals(1.4638999700546265f.toBits(), local[12].toBits())
        assertEquals(1f, local[18], 0f)
        assertEquals(13.937f, glowMatrix(true, .9f)[0], .0001f)
    }
    @Test fun everyBeginHasIdentityAndDissipationUsesAbsoluteTargetsAfterStrictTravel() {
        val body = GlassMomentum()
        body.targetGlow(Offset(.2f, .4f), 1f, 1f, generation = 1)
        body.advance(0f, .05)
        val prior = current(body); val alpha = body.glow.little
        body.targetGlow(Offset(.8f, .6f), 1f, 1f, generation = 2)
        assertSame(prior, records(body).first()); assertEquals(2, records(body).size)
        assertEquals(0f, body.glow.little, 0f); assertEquals(alpha, body.glow.retiring.single().little, 0f)
        body.targetGlow(Offset(.8f, .6f), 1f, 1f, travel = 50f, generation = 2)
        assertEquals(1f, light(body, 5, "target"), 0f)
        body.targetGlow(Offset(.8f, .6f), 1f, 1f, travel = Math.nextUp(50f), generation = 2)
        assertEquals(.5f, light(body, 1, "target"), 0f); assertEquals(2f, light(body, 5, "target"), 0f)
        body.advance(0f, .04)
        val local = current(body); val age = local.javaClass.getDeclaredField("elapsed").apply { isAccessible = true }.getDouble(local)
        repeat(20) { body.targetGlow(Offset(.8f, .6f), 1f, 1f, travel = 60f, generation = 2) }
        assertEquals(.5f, light(body, 1, "target"), 0f)
        assertEquals(age, local.javaClass.getDeclaredField("elapsed").apply { isAccessible = true }.getDouble(local), 0.0)
        body.targetGlow(Offset(.8f, .6f), 0f, 1f, generation = 2)
        assertEquals(4f, light(body, 5, "target"), 0f)
        body.advance(0f, 10.0); body.finishSettlement()
        assertTrue(records(body).isEmpty()); assertTrue(body.settled)
    }

    @Test fun aZeroCurrentAlphaCannotRemoveARecordWhoseFutureEnvelopeIsVisible() {
        val body = GlassMomentum()
        body.targetGlow(Offset(.5f, .5f), 1f, 1f)
        val local = current(body)
        val channel = (local.javaClass.getDeclaredField("light").apply { isAccessible = true }.get(local) as List<*>).first()!!
        channel.javaClass.getDeclaredField("velocity").apply { isAccessible = true }.setFloat(channel, 1f)
        body.targetGlow(Offset(.5f, .5f), 0f, 1f)
        body.advance(0f, 1e-100)
        assertEquals(0f, body.glow.little, 0f); assertSame(local, current(body))
        body.advance(0f, .05)
        assertTrue(body.glow.little > 0f)
        repeat(1200) { body.advance(0f, 1.0 / 120); body.finishSettlement() }
        assertTrue(records(body).isEmpty()); assertTrue(body.settled)
    }

    @Test fun inverseCentreCancelsTheSingleBodyMapAcrossDensities() {
        for (density in listOf(2f, 2.48125f, 3f)) for (raw in listOf(Offset(.1f, .8f), Offset(1.25f, -.25f))) {
            val bounds = Rect(7f * density, 11f * density, 103f * density, 91f * density)
            val shape = GlassShape(.08f, -.2f)
            val scale = Offset(shape.scaleX(), shape.scaleY())
            val drift = glassContactDrift(raw, bounds.size, scale)
            val tracked = Offset(.7f, .3f)
            val local = glowCentre(tracked, bounds, scale, drift)
            val expected = listOf(bounds.left.toDouble() + bounds.width.toDouble() * tracked.x,
                bounds.top.toDouble() + bounds.height.toDouble() * tracked.y)
            for (axis in 0..1) {
                val u = if (axis == 0) local.x else local.y
                val o = if (axis == 0) bounds.center.x else bounds.center.y
                val a = if (axis == 0) scale.x else scale.y
                val d = if (axis == 0) drift.x else drift.y
                val world = o + a.toDouble() * (u.toDouble() - o) + d
                val storageBound = Math.ulp(u).toDouble() * abs(a) + Math.ulp(o) + Math.ulp(d) + Math.ulp(expected[axis].toFloat())
                assertTrue("density=$density axis=$axis error=" + abs(world - expected[axis]), abs(world - expected[axis]) <= storageBound)
            }
        }
    }

}
