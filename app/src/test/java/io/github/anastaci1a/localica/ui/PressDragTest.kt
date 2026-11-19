package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PressDragTest {
    private fun held(to: Offset, extent: Size = Size(44f, 44f), vertical: Boolean = false, spec: FlexSpec = FlexSpec.Small): GlassShape {
        val body = GlassMomentum().apply { this.spec = spec; this.extent = extent }
        body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f), vertical = vertical)
        repeat(480) { body.advance(0f, 1.0 / 120, contact = 1f, point = to, vertical = vertical) }
        return body.shape
    }

    @Test fun translationThresholdFollowsTheSizeFactory() {
        assertEquals(2000f, FlexSpec.Small.distance, 0f)
        assertEquals(2000f, FlexSpec.dynamic(119f, 44f).distance, 0f)
        assertEquals(6000f, FlexSpec.dynamic(160f, 44f).distance, 0f)
        assertEquals(15000f, FlexSpec.dynamic(240f, 102f).distance, 0f)
        assertEquals(24000f, FlexSpec.dynamic(240f, 160f).distance, 0f)
    }

    @Test fun pullHasNoBoxClampAndADiagonalFollowsTheSameLaw() {
        val reach = Offset(44f, 44f) / 2000f
        val centre = Offset(.5f, .5f)
        var last = 0f
        for (widths in listOf(.5f, 1f, 2f, 4f, 8f)) {
            val axis = glassContactFlex(centre + Offset(widths, 0f), centre, reach)
            assertEquals(0f, axis.y, 0f)
            assertTrue("drift keeps growing past the edge at $widths", axis.x > last); last = axis.x
            val diagonal = glassContactFlex(centre + Offset(widths, widths) / sqrt(2f), centre, reach)
            assertEquals(glassContactFlex(centre + Offset(widths / sqrt(2f), 0f), centre, reach).x, diagonal.x, 0f)
            assertEquals(diagonal.x, diagonal.y, 0f)
        }
        assertEquals(.022f * (.5f / 4 + 100f / 44 - .5f), glassContactFlex(centre + Offset(100f / 44, 0f), centre, reach).x, 1e-6f)
        val far = held(Offset(.5f + 300f / 44, .5f)); val near = held(Offset(.5f + 100f / 44, .5f))
        assertTrue(far.drift.x > 2 * near.drift.x); assertTrue(far.strain > 2 * near.strain)
        val square = held(Offset(.5f + 200f / 44, .5f + 200f / 44))
        assertEquals(square.drift.x, square.drift.y, 1e-6f); assertEquals(0f, square.strain, 1e-6f)
    }

    @Test fun nearEndTravelsFartherThanTheHeldFarEnd() {
        val size = Size(132f, 132f)
        fun edges(shape: GlassShape): Pair<Float, Float> {
            val map = shape.bodyTransform(size)
            fun world(u: Float) = size.width / 2 + map.scale.x * (u - size.width / 2) + map.drift.x
            return world(size.width) to world(0f)
        }
        for (pull in listOf(60f, 150f, 300f)) {
            val (stillNear, stillFar) = edges(held(Offset(.5f, .5f)))
            val (pulledNear, pulledFar) = edges(held(Offset(.5f + pull / 44, .5f)))
            val nearMove = pulledNear - stillNear; val farMove = pulledFar - stillFar
            assertTrue("pull=$pull far end follows", farMove > 0f)
            assertTrue("pull=$pull near=$nearMove far=$farMove", nearMove > 2.5f * farMove)
        }
    }

    @Test fun overrideBodiesKeepNoLiftButFollowTheSameTranslationLaw() {
        val to = Offset(.5f + 300f / 44, .5f)
        val override = GlassMomentum(FlexSpec.Small).apply { spec = FlexSpec.dynamic(200f, 44f); extent = Size(200f, 44f) }
        val ordinary = GlassMomentum().apply { spec = FlexSpec.dynamic(200f, 44f); extent = Size(200f, 44f) }
        for (body in listOf(override, ordinary)) {
            body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
            body.advance(0f, 0.0, contact = 1f, point = to)
        }
        fun strainTarget(b: GlassMomentum) = GlassMomentum::class.java.getDeclaredField("pressure").apply { isAccessible = true }.get(b)
            .let { s -> s.javaClass.getDeclaredField("target").apply { isAccessible = true }.getFloat(s) }
        assertEquals(0f, override.shape.lift, 0f)
        assertTrue(strainTarget(override) > 0f)
        assertEquals(strainTarget(ordinary), strainTarget(override), 0f)
        repeat(240) { override.advance(0f, 1.0 / 120, contact = 1f, point = to) }
        val expected = glassContactFlex(to, Offset(.5f, .5f), Offset(200f, 44f) / 6000f)
        assertEquals(expected.x, override.shape.drift.x, 1e-4f); assertEquals(0f, override.shape.drift.y, 0f)
        assertEquals(0f, override.shape.lift, 0f)
    }

    @Test fun fastDragMeasuresFromTheDownPointEvenWhenTheFirstSampleIsOutside() {
        val reach = Offset(44f, 44f) / 2000f
        val body = GlassMomentum().apply { extent = Size(44f, 44f); origin = Offset(.5f, .5f) }
        body.advance(0f, 0.0, contact = 1f, point = Offset(1.5f, .5f))
        body.advance(0f, 1.0 / 120, contact = 1f, point = Offset(1.6f, .5f))
        val target = GlassMomentum::class.java.getDeclaredField("drift").apply { isAccessible = true }.get(body) as List<*>
        val x = target[0]!!.let { s -> s.javaClass.getDeclaredField("target").apply { isAccessible = true }.getFloat(s) }
        assertEquals(glassContactFlex(Offset(1.6f, .5f), Offset(.5f, .5f), reach).x, x, 0f)
        assertTrue("pull keeps its direction", x > 0f)
        body.origin = Offset(.2f, .5f)
        body.advance(0f, 1.0 / 120, contact = 1f, point = Offset(.2f, .5f))
        assertEquals("a new DOWN re-anchors without a sampled release", 0f,
            target[0]!!.let { s -> s.javaClass.getDeclaredField("target").apply { isAccessible = true }.getFloat(s) }, 0f)
    }

    @Test fun heldLiftAddsTheSizeLawPointsToTheLongEdgeOnBothAxes() {
        fun points(long: Double, short: Double) = if (long < 120) 16.0 else 16 - 12 * ((short - 44) / 116).coerceIn(0.0, 1.0)
        val sizes = listOf(Size(44f, 44f), Size(119f, 119f), Size(120f, 120f), Size(120f, 44f), Size(328f, 40f), Size(40f, 328f), Size(160f, 160f), Size(240f, 102f))
        for (size in sizes) for (density in listOf(1f, 2.625f, 3f)) {
            val long = size.maxDimension.toDouble(); val short = size.minDimension.toDouble()
            val body = GlassMomentum().apply { spec = FlexSpec.dynamic(long.toFloat(), short.toFloat()); extent = size }
            body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
            repeat(960) { body.advance(0f, 1.0 / 120, contact = 1f, point = Offset(.5f, .5f)) }
            assertTrue("held press settles at its target size=$size", body.finishSettlement())
            val pixels = Size(size.width * density, size.height * density)
            val map = body.shape.bodyTransform(pixels)
            val p = points(long, short)
            assertEquals("uniform lift size=$size", map.scale.x, map.scale.y, 0f)
            assertEquals("long edge grows by P size=$size", p * density, (if (size.width >= size.height) pixels.width * (map.scale.x - 1) else pixels.height * (map.scale.y - 1)).toDouble(), 1e-3)
            assertEquals("short edge grows by P·S/L size=$size", p * short / long * density, (minOf(pixels.width * (map.scale.x - 1), pixels.height * (map.scale.y - 1))).toDouble(), 1e-3)
            assertEquals(Offset.Zero, map.drift)
        }
        assertEquals(4f, FlexSpec.dynamic(160f, 160f).lift.points, 0f); assertEquals(16f, FlexSpec.dynamic(120f, 44f).lift.points, 0f)
        assertEquals(16f, FlexSpec.Small.lift.points, 0f)
        val slot = GlassMomentum().apply { spec = FlexSpec.dynamic(328f, 40f); extent = Size(328f, 40f) }
        slot.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
        repeat(960) { slot.advance(0f, 1.0 / 120, contact = 1f, point = Offset(.5f, .5f)) }
        slot.finishSettlement()
        val half = 328f * slot.shape.bodyTransform(Size(328f, 40f)).scale.x / 2
        assertEquals(8f, 180f - half, 1e-3f); assertEquals(352f, 180f + half, 1e-3f)
        val unsized = GlassMomentum().apply { spec = FlexSpec.dynamic(328f, 40f) }
        repeat(120) { unsized.advance(0f, 1.0 / 120, contact = 1f, point = Offset(.5f, .5f)) }
        assertEquals(0f, unsized.shape.lift, 0f)
    }

    @Test fun ordinaryAxesDropTheAreaTermWhileConditionalAndAnchoredBodiesKeepTheirTransformBits() {
        val size = Size(300f, 120f)
        for (vertical in listOf(false, true)) for (shape in listOf(GlassShape(.1f, -.2f), GlassShape(-.07f, -.24f, lift = .05f, drift = Offset(.02f, -.01f)))) {
            val s = if (vertical) -shape.strain else shape.strain
            val map = shape.bodyTransform(size, vertical)
            assertEquals(Offset(exp(s) * (1f + shape.lift), exp(-s) * (1f + shape.lift)), map.scale)
            assertEquals(Offset(shape.drift.x * 300f, shape.drift.y * 120f), map.drift)
            val anchored = shape.bodyTransform(size, vertical, anchored = true)
            assertEquals(GlassBodyTransform(Offset(shape.scaleX(vertical) * exp(0f), shape.scaleY(vertical) * exp(0f))), anchored)
        }
        val conditional = GlassMomentum(FlexSpec.Small.copy(scaleGain = -(16f / 64f) / .24f)).apply { extent = Size(64f, 64f) }
        conditional.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
        repeat(30) { conditional.advance(2f, 1.0 / 120, contact = 1f, point = Offset(.9f, .5f)) }
        val shape = conditional.shape
        assertEquals(0f, shape.lift, 0f); assertTrue(shape.scale != null && shape.depth < 0f)
        for (vertical in listOf(false, true)) assertEquals(GlassBodyTransform(Offset(shape.scaleX(vertical) * exp(shape.lift), shape.scaleY(vertical) * exp(shape.lift)),
            Offset(shape.drift.x * 64f, shape.drift.y * 64f)), shape.bodyTransform(Size(64f, 64f), vertical))
    }

    @Test fun verticalBodiesStretchInScreenSpaceAndReleaseSettles() {
        val size = Size(44f, 44f); val to = Offset(.5f + 150f / 44, .2f)
        assertEquals(held(to).bodyTransform(size), held(to, vertical = true).bodyTransform(size, vertical = true))
        val body = GlassMomentum().apply { extent = size }
        body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
        repeat(60) { body.advance(0f, 1.0 / 120, contact = 1f, point = to) }
        assertTrue(body.shape.drift.x > 0f)
        repeat(600) { body.advance(0f, 1.0 / 120) }
        assertTrue(body.finishSettlement()); assertEquals(GlassShape(), body.shape)
    }
}
