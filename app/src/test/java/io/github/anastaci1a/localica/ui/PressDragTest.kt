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
            val map = shape.bodyTransform(size, 1f)
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

    @Test fun verticalBodiesStretchInScreenSpaceAndReleaseSettles() {
        val size = Size(44f, 44f); val to = Offset(.5f + 150f / 44, .2f)
        assertEquals(held(to).bodyTransform(size, 1f), held(to, vertical = true).bodyTransform(size, 1f, vertical = true))
        val body = GlassMomentum().apply { extent = size }
        body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
        repeat(60) { body.advance(0f, 1.0 / 120, contact = 1f, point = to) }
        assertTrue(body.shape.drift.x > 0f)
        repeat(600) { body.advance(0f, 1.0 / 120) }
        assertTrue(body.finishSettlement()); assertEquals(GlassShape(), body.shape)
    }

    private fun settled(size: Size, to: Offset = Offset(.5f, .5f)) = GlassMomentum().apply { spec = FlexSpec.dynamic(size.maxDimension, size.minDimension); extent = size }.run {
        advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
        repeat(960) { advance(0f, 1.0 / 120, contact = 1f, point = to) }
        assertTrue("held press settles size=$size", finishSettlement())
        shape
    }

    @Test fun heldLiftAddsTheSizeLawPointsToTheLongEdgeOfEveryBodyAsDrawn() {
        fun points(long: Double, short: Double) = if (long < 120) 16.0 else 16 - 12 * ((short - 44) / 116).coerceIn(0.0, 1.0)
        // Circle buttons, the album play button, the Sort/View pill, the split pill, wide bars and the scoped search bar.
        val sizes = listOf(Size(51.2f, 51.2f), Size(64f, 64f), Size(110f, 51.2f), Size(119f, 119f), Size(120f, 120f), Size(120f, 44f),
            Size(144f, 64f), Size(328f, 40f), Size(40f, 328f), Size(160f, 160f), Size(240f, 102f))
        for (size in sizes) for (density in listOf(1f, 2.625f, 3f)) {
            val long = size.maxDimension.toDouble(); val short = size.minDimension.toDouble()
            val pixels = Size(size.width * density, size.height * density)
            val map = settled(size).bodyTransform(pixels, density)
            val p = points(long, short)
            assertEquals("uniform lift size=$size", map.scale.x, map.scale.y, 0f)
            assertEquals("long edge grows by P size=$size", p * density, (maxOf(pixels.width * (map.scale.x - 1), pixels.height * (map.scale.y - 1))).toDouble(), 1e-3 * density)
            assertEquals("short edge grows by P·S/L size=$size", p * short / long * density, (minOf(pixels.width * (map.scale.x - 1), pixels.height * (map.scale.y - 1))).toDouble(), 1e-3 * density)
            assertEquals(Offset.Zero, map.drift)
        }
        assertEquals(4f, FlexSpec.dynamic(160f, 160f).lift.points, 0f); assertEquals(16f, FlexSpec.dynamic(120f, 44f).lift.points, 0f)
        assertEquals(16f, FlexSpec.Small.lift.points, 0f)
        // A 328 dp search bar with 16 dp window insets stays inside a 360 dp window.
        val half = 328f * settled(Size(328f, 40f)).bodyTransform(Size(328f, 40f), 1f).scale.x / 2
        assertEquals(8f, 180f - half, 1e-3f); assertEquals(352f, 180f + half, 1e-3f)
        assertEquals(Offset(1f, 1f), settled(Size(64f, 64f)).bodyTransform(Size.Zero, 2f).scale)
    }

    @Test fun aHeldBodyDrawnAtANewSizeGrowsByTheSameDpWithoutARepublish() {
        val shape = settled(Size(104f, 48f))
        for ((size, density) in listOf(Size(104f, 48f) to 2f, Size(240f, 104f) to 2f, Size(240f, 104f) to 3f)) {
            val map = shape.bodyTransform(Size(size.width * density, size.height * density), density)
            assertEquals("size=$size", 16f * density, size.width * density * (map.scale.x - 1), 1e-3f * density)
        }
    }

    @Test fun popupTriggersAndPlainBodiesNearEndTravelsFartherOnEveryClass() {
        for (size in listOf(Size(51.2f, 51.2f), Size(64f, 64f), Size(110f, 51.2f), Size(144f, 64f), Size(328f, 40f))) {
            fun edges(shape: GlassShape): Pair<Float, Float> {
                val map = shape.bodyTransform(size, 1f)
                fun world(u: Float) = size.width / 2 + map.scale.x * (u - size.width / 2) + map.drift.x
                return world(size.width) to world(0f)
            }
            val (stillNear, stillFar) = edges(settled(size))
            val (pulledNear, pulledFar) = edges(settled(size, Offset(.5f + 150f / size.width, .5f)))
            val nearMove = pulledNear - stillNear; val farMove = pulledFar - stillFar
            assertTrue("size=$size far end follows far=$farMove", farMove > 0f)
            assertTrue("size=$size near=$nearMove far=$farMove", nearMove > 2f * farMove)
        }
    }

    @Test fun overrideBodiesTakeTheirPointsFromTheSameSelector() {
        for (size in listOf(Size(51.2f, 51.2f), Size(160f, 51.2f), Size(240f, 102f))) {
            val p = FlexSpec.dynamic(size.maxDimension, size.minDimension).lift.points
            val body = GlassMomentum(FlexSpec.Small.copy(scaleGain = -(p / size.maxDimension) / .24f)).apply { extent = size }
            body.advance(0f, 0.0, contact = 1f, point = Offset(.5f, .5f))
            repeat(960) { body.advance(0f, 1.0 / 120, contact = 1f, point = Offset(.5f, .5f)) }
            body.finishSettlement()
            assertEquals("size=$size", p, size.maxDimension * (body.shape.bodyTransform(size, 1f).scale.x - 1), 1e-3f)
        }
    }
}
