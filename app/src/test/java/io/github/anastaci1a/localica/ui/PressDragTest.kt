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
