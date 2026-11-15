package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.spec.FlexGlow
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

class GlassGlowTest {
    private fun step(body: GlassMomentum, fps: Int, point: Offset?, held: Float, strength: Float = 1f) {
        body.targetGlow(point, held, strength)
        body.advance(0f, 1f / fps, contact = held)
        body.finishSettlement()
    }
    private fun critical(time: Double, response: Double): Float {
        val w = 2 * PI / response
        return (1 - (1 + w * time) * exp(-w * time)).toFloat()
    }
    private fun channels(body: GlassMomentum): List<List<Float>> {
        val field = body.javaClass.getDeclaredField("light").apply { isAccessible = true }
        return (field.get(body) as List<*>).map { spring ->
            listOf("value", "velocity", "target").map { name ->
                spring!!.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(spring)
            }
        }
    }

    @Test fun extractedTimingAndOpacitySelectionKeepSpatialValuesExplicit() {
        assertEquals(.1f, FlexGlow.Show, 0f)
        assertEquals(.5f, FlexGlow.Hide, 0f)
        assertEquals(.15f, FlexGlow.Follow, 0f)
        assertEquals(.12f, FlexGlow.Gain, 0f)
        assertEquals(.75f, FlexGlow.ExtentScale, 0f)
        assertEquals(80f, FlexGlow.ExtentCap, 0f)
        assertEquals(listOf(0f to 1f, 1f to 0f), FlexGlow.Falloff)
        for (short in listOf(44f, 102f, 160f)) {
            val t = ((short - 44f) / 116f).coerceIn(0f, 1f)
            val spec = FlexSpec.dynamic(240f, short)
            assertEquals(1 - t, spec.bigOpacity, 0f)
            assertEquals(.3f * (1 - t) + .2f * t, spec.littleOpacity, .000001f)
            assertEquals(FlexSpec.dynamic(240f).response, spec.response, 0f)
        }
        assertEquals(1f, FlexSpec.dynamic(119f, 44f).littleOpacity, 0f)
        assertEquals(.3f, FlexSpec.dynamic(120f, 44f).littleOpacity, 0f)
    }

    @Test fun showFollowAndHideMatchIndependentCriticalResponsesAcrossFrameRates() {
        var worst = 0f
        for (fps in listOf(30, 60, 120)) {
            val body = GlassMomentum()
            val down = Offset(.25f, .75f)
            body.targetGlow(down, 1f, 1f)
            assertEquals(down, body.glow.point)
            repeat(fps) { frame ->
                step(body, fps, down, 1f)
                val expected = critical((frame + 1.0) / fps, .1)
                val error = abs(expected - body.glow.big)
                worst = maxOf(worst, error)
                assertTrue("show fps=$fps frame=$frame error=$error", error <= .09f)
                assertEquals(body.glow.big, body.glow.little, 0f)
            }
            assertEquals(1f, body.glow.big, 0f)
            val moved = Offset(1.25f, -.25f)
            repeat(fps) { frame ->
                step(body, fps, moved, 1f)
                val progress = critical((frame + 1.0) / fps, .15)
                assertEquals(down.x + progress, body.glow.point.x, .06f)
                assertEquals(down.y - progress, body.glow.point.y, .06f)
            }
            repeat(fps * 2) { frame ->
                step(body, fps, moved, 0f)
                val expected = 1 - critical((frame + 1.0) / fps, .5)
                assertEquals("hide fps=$fps frame=$frame", expected, body.glow.big, .02f)
            }
            assertEquals(0f, body.glow.big, 0f)
            assertEquals(0f, body.glow.little, 0f)
            assertTrue(body.settled)
        }
        println("critical show max absolute error=$worst bound=0.09")
    }

    @Test fun repressKeepsAlphaEnergyAndSnapsOnlyTheNewCentre() {
        for (releaseFrames in listOf(1, 4, 12, 30)) {
            val body = GlassMomentum()
            repeat(60) { step(body, 120, Offset(.2f, .7f), 1f) }
            repeat(releaseFrames) { step(body, 120, Offset(.2f, .7f), 0f) }
            val before = channels(body)
            body.targetGlow(Offset(.8f, .3f), 1f, 1f)
            val after = channels(body)
            for (channel in 0..1) for (field in 0..1) assertEquals(before[channel][field], after[channel][field], 0f)
            assertEquals(Offset(.8f, .3f), body.glow.point)
            assertEquals(0f, after[2][1], 0f)
            assertEquals(0f, after[3][1], 0f)
            repeat(240) { step(body, 120, Offset(.8f, .3f), 1f) }
            assertEquals(1f, body.glow.big, 0f)
        }
    }

    @Test fun shapeSettlementIsIndependentAndInvisibleGlowCannotHoldItOpen() {
        for (fps in listOf(30, 60, 120)) for (strength in listOf(0f, 1f)) {
            val lit = GlassMomentum()
            val plain = GlassMomentum()
            repeat(fps * 5) { frame ->
                val contact = if (frame < fps / 3 || frame in fps..fps + fps / 4) 1f else 0f
                step(lit, fps, Offset(.1f + frame % 3 * .4f, .5f), contact, strength)
                step(plain, fps, null, contact)
                assertEquals("shape frame=$frame strength=$strength", plain.shape, lit.shape)
                if (strength == 0f) {
                    assertEquals(GlassGlow(), lit.glow)
                    assertEquals("disabled settlement frame=$frame", plain.settled, lit.settled)
                }
            }
            assertTrue(lit.settled)
            assertEquals(GlassShape(), lit.shape)
            assertEquals(0f, lit.glow.big, 0f)
        }
    }

    @Test fun blendingTransfersEveryGlowValueVelocityAndTargetWithoutAxisRotation() {
        val a = GlassMomentum().apply { spec = FlexSpec.dynamic(160f, 80f) }
        val b = GlassMomentum()
        repeat(3) { step(a, 120, Offset(.1f, .2f), 1f) }
        repeat(7) { step(b, 120, Offset(.8f, .9f), 1f) }
        step(a, 120, Offset(.7f, .4f), 1f)
        step(b, 120, Offset(.2f, .3f), 0f)
        val left = channels(a)
        val right = channels(b)
        val joined = GlassMomentum()
        joined.blendFrom(a, b, .25f, -1f, 1f)
        val actual = channels(joined)
        for (channel in 0..3) for (field in 0..2)
            assertEquals(left[channel][field] * .75f + right[channel][field] * .25f, actual[channel][field], 0f)
        joined.reset()
        assertEquals(GlassGlow(), joined.glow)
        assertTrue(joined.settled)
    }

    @Test fun reducedAndDisabledInputsSnapWithoutDiscardingTheContact() {
        val body = GlassMomentum()
        step(body, 120, Offset(.2f, .7f), 1f)
        body.targetGlow(Offset(.8f, .3f), 1f, .5f, snap = true)
        assertEquals(GlassGlow(.5f, .5f, Offset(.8f, .3f)), body.glow)
        body.resetShape()
        assertEquals(GlassShape(), body.shape)
        assertEquals(GlassGlow(.5f, .5f, Offset(.8f, .3f)), body.glow)
        body.targetGlow(Offset(.1f, .9f), 0f, .5f, snap = true)
        assertEquals(GlassGlow(0f, 0f, Offset(.8f, .3f)), body.glow)
        body.targetGlow(Offset(.8f, .3f), 1f, 0f)
        assertEquals(GlassGlow(), body.glow)
        body.targetGlow(null, 1f, 1f)
        assertEquals(GlassGlow(), body.glow)
    }
}
