package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.spec.FlexGlow
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import java.io.File
import java.security.MessageDigest

class GlassGlowTest {
    private fun step(body: GlassMomentum, fps: Int, point: Offset?, held: Float, strength: Float = 1f, far: Boolean = false) {
        body.targetGlow(point, held, strength, far)
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
        assertEquals(.05f, FlexGlow.Lift, 0f)
        assertEquals(50f, FlexGlow.Travel, 0f)
        assertEquals(listOf(.45f, .85f), FlexGlow.Dodge)
        assertEquals(listOf(1f to 1f, .5f to 2f, 0f to 4f), FlexGlow.Phase)
        assertEquals(.75f, FlexGlow.ExtentScale, 0f)
        assertEquals(80f, FlexGlow.ExtentCap, 0f)
        // Integrate the unit disc against a unit-sigma 2D Gaussian, independently of its sampled table.
        fun profile(distance: Double): Double {
            var sum = 0.0
            for (r in 0 until 160) for (a in 0 until 320) {
                val radius = (r + .5) / 160
                val angle = (a + .5) * 2 * PI / 320
                sum += exp(-(radius * radius + distance * distance - 2 * radius * distance * kotlin.math.cos(angle)) / 2) * radius / (160 * 320)
            }
            return sum
        }
        assertEquals(1 - exp(-.5), profile(0.0), .00001)
        for (sample in 0..140) {
            val radius = sample / 40.0
            val index = minOf(6, (radius * 2).toInt())
            val fraction = (radius * 2 - index).toFloat()
            val a = FlexGlow.Falloff[index]
            val b = FlexGlow.Falloff[index + 1]
            assertEquals(index / 7f, a.first, 0f)
            assertEquals("profile radius=$radius", profile(radius), (a.second + (b.second - a.second) * fraction).toDouble(), .009)
        }
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
            repeat(releaseFrames) {
                step(body, 120, Offset(.2f, .7f), 0f)
                assertTrue(body.glow.big in 0f..1f && body.glow.little in 0f..1f)
            }
            val before = channels(body)
            body.targetGlow(Offset(.8f, .3f), 1f, 1f)
            val after = channels(body)
            for (channel in 0..1) for (field in 0..1) assertEquals(before[channel][field], after[channel][field], 0f)
            assertEquals(Offset(.8f, .3f), body.glow.point)
            assertEquals(0f, after[2][1], 0f)
            assertEquals(0f, after[3][1], 0f)
            assertEquals(listOf(1f, 0f, 1f), after[4])
            repeat(240) {
                step(body, 120, Offset(.8f, .3f), 1f)
                assertTrue(body.glow.big in 0f..1f && body.glow.little in 0f..1f)
            }
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
        for (channel in 0..4) for (field in 0..2)
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
        body.targetGlow(Offset(.1f, .9f), 0f, .5f, snap = true)
        assertEquals(0f, body.glow.big, 0f)
        assertEquals(0f, body.glow.little, 0f)
        assertEquals(listOf(4f, 0f, 4f), channels(body)[4])
        body.targetGlow(Offset(.8f, .3f), 1f, 0f)
        assertEquals(GlassGlow(), body.glow)
        body.targetGlow(null, 1f, 1f)
        assertEquals(GlassGlow(), body.glow)
    }

    @Test fun dissipationSuppressesInheritedVelocityOnceAndMatchesFiveChannelOracle() {
        val point = Offset(.4f, .6f)
        for (fps in listOf(30, 60, 120)) for (crossingMs in listOf(1, 8, 50, 200)) {
            val body = GlassMomentum()
            body.targetGlow(point, 1f, 1f)
            repeat(crossingMs) { body.advance(0f, .001f, contact = 1f) }
            val before = channels(body)
            body.targetGlow(point, 1f, 1f, far = true)
            val after = channels(body)
            assertEquals(before[0], after[0])
            for (channel in listOf(1, 4)) {
                assertEquals(before[channel][0], after[channel][0], 0f)
                assertEquals(0f, after[channel][1], 0f)
            }
            assertEquals(.5f, after[1][2], 0f)
            assertEquals(2f, after[4][2], 0f)
            repeat(fps * 2) { frame ->
                val current = channels(body)
                body.targetGlow(point, 1f, 1f, far = true)
                assertEquals("unchanged far target retains energy", current, channels(body))
                body.advance(0f, 1f / fps, contact = 1f)
                body.finishSettlement()
                val progress = critical((frame + 1.0) / fps, .5)
                assertEquals("dissipated alpha fps=$fps crossing=$crossingMs", before[1][0] + (.5f - before[1][0]) * progress, body.glow.little, .02f)
                assertEquals("dissipated scale fps=$fps crossing=$crossingMs", 1 + progress, body.glow.scale, .02f)
            }
            assertEquals(.5f, body.glow.little, 0f)
            assertEquals(2f, body.glow.scale, 0f)
            repeat(fps * 2) { frame ->
                step(body, fps, point, 0f, far = true)
                val progress = critical((frame + 1.0) / fps, .5)
                assertEquals(.5f * (1 - progress), body.glow.little, .02f)
                // Hidden geometry finishes with alpha, rather than extending the clock.
                if (body.glow.little != 0f) assertEquals(2 + 2 * progress, body.glow.scale, .04f)
            }
            assertEquals(4f, body.glow.scale, 0f)
            assertTrue(body.settled)
            body.targetGlow(point, 1f, 1f)
            repeat(8) { body.advance(0f, .001f, contact = 1f) }
            body.targetGlow(point, 1f, 1f, far = true)
            assertEquals(0f, channels(body)[1][1], 0f)
            assertEquals(0f, channels(body)[4][1], 0f)
            body.reset()
            assertEquals(GlassGlow(), body.glow)
            assertEquals(listOf(1f, 0f, 1f), channels(body)[4])
        }
    }

    @Test fun hiddenGeometryFinishesWithoutSettlingActiveShapeOrVisibleTargets() {
        val body = GlassMomentum()
        body.targetGlow(Offset(.3f, .7f), 1f, 1f)
        repeat(4) { body.advance(0f, .001f, contact = 1f) }
        body.targetGlow(Offset(.3f, .7f), 0f, 1f)
        val light = body.javaClass.getDeclaredField("light").apply { isAccessible = true }.get(body) as List<*>
        fun put(channel: Int, name: String, value: Float) {
            val spring = light[channel]!!
            spring.javaClass.getDeclaredField(name).apply { isAccessible = true }.setFloat(spring, value)
        }
        for (channel in 0..1) { put(channel, "value", 0f); put(channel, "velocity", 0f) }
        put(4, "value", 1.3f)
        put(4, "velocity", 3f)
        assertFalse(body.settled)
        val shape = body.shape
        assertFalse("hidden completion does not finish active shape", body.finishSettlement())
        assertEquals(shape, body.shape)
        assertEquals(listOf(4f, 0f, 4f), channels(body)[4])
        repeat(600) { body.advance(0f, 1f / 120); body.finishSettlement() }
        put(4, "value", 1.3f)
        put(4, "velocity", 3f)
        assertFalse(body.settled)
        assertTrue("hidden scale alone cannot keep the clock alive", body.finishSettlement())
        body.targetGlow(Offset(.9f, .1f), 1f, 1f)
        put(1, "value", 0f)
        put(4, "value", 1.3f)
        put(4, "velocity", 3f)
        body.finishSettlement()
        assertEquals("nonzero target must retain geometry energy", listOf(1.3f, 3f, 1f), channels(body)[4])
    }

    @Test fun targetDropAndReleaseKeepSignedEnergyOutsideTheDrawableRange() {
        for (ms in 1..99) {
            val body = GlassMomentum()
            body.targetGlow(Offset(.5f, .5f), 1f, 1f)
            repeat(ms) { body.advance(0f, .001f, contact = 1f); body.finishSettlement() }
            val before = channels(body)
            body.targetGlow(Offset(.5f, .5f), 0f, 1f)
            val after = channels(body)
            for (channel in 0..1) for (field in 0..1) assertEquals(before[channel][field], after[channel][field], 0f)
            repeat(2000) {
                body.advance(0f, .001f)
                body.finishSettlement()
                assertTrue("release at $ms ms", body.glow.big in 0f..1f && body.glow.little in 0f..1f)
            }
            assertEquals(0f, body.glow.big, 0f)
            assertEquals(0f, body.glow.little, 0f)
        }
        for (releaseFrames in 1..4) {
            val body = GlassMomentum()
            repeat(120) { step(body, 120, Offset(.5f, .5f), 1f) }
            body.spec = FlexSpec.dynamic(240f, 160f)
            repeat(releaseFrames) { step(body, 120, Offset(.5f, .5f), 1f) }
            var negative = false
            repeat(360) {
                step(body, 120, Offset(.5f, .5f), 0f)
                for (alpha in listOf(body.glow.big, body.glow.little)) {
                    negative = negative || alpha < 0f
                    assertTrue("only positive energy may reach paint", maxOf(0f, alpha) in 0f..1f)
                }
            }
            assertTrue("velocity survives the show/hide frequency change", negative)
            assertTrue(body.settled)
        }
    }

    @Test fun frozenOpticsMatchTheAcceptedSliceOneSource() {
        val root = File("src/main/java/io/github/anastaci1a/localica/ui")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Pinned at accepted d72063fe4625cece1021f9d9a11fae061d071b4c, not regenerated from this candidate.
        assertEquals("1be7b81ccf3d1f869658d46341661c44004b4ff216c95f12a164eb41c4811d7c", sha(File(root, "LensRenderer.kt").readBytes()))
        val source = File(root, "LiquidGlass.kt").readText()
        val start = source.indexOf("if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(")
        val effect = source.substring(start, source.indexOf("\n                else if", start))
        assertEquals("20e598e80a1ac997cfb4d02c22a9c8ceffb5f152de28344c6e71ff53ef954892", sha(effect.toByteArray()))
    }
}
