package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.PopupWindowMotion
import io.github.anastaci1a.localica.ui.spec.ExpansionGeometryDomain
import io.github.anastaci1a.localica.ui.spec.FlexGlow
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.PopupMotionStyle
import io.github.anastaci1a.localica.ui.spec.SpecResult
import io.github.anastaci1a.localica.ui.spec.uiSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import java.io.File
import java.security.MessageDigest

class GlassGlowTest {
    private fun step(body: GlassMomentum, fps: Int, point: Offset?, held: Float, strength: Float = 1f) {
        body.targetGlow(point, held, strength)
        body.advance(0f, (1f / fps).toDouble(), contact = held)
        body.finishSettlement()
    }
    private fun critical(time: Double, response: Double): Float {
        val w = 2 * PI / response
        return (1 - (1 + w * time) * exp(-w * time)).toFloat()
    }
    private fun records(body: GlassMomentum) = body.javaClass.getDeclaredField("locals").apply { isAccessible = true }.get(body) as List<*>
    private fun channels(body: GlassMomentum): List<List<Float>> {
        val broad = body.javaClass.getDeclaredField("broad").apply { isAccessible = true }.get(body)
        val local = records(body).lastOrNull()?.let { it.javaClass.getDeclaredField("light").apply { isAccessible = true }.get(it) as List<*> } ?: emptyList<Any>()
        return (listOf(broad) + local).map { spring -> listOf("value", "velocity", "target").map { name -> spring!!.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(spring) } }
    }

    @Test fun extractedTimingAndOpacitySelectionKeepSpatialValuesExplicit() {
        assertEquals(.1f, FlexGlow.Show, 0f)
        assertEquals(.5f, FlexGlow.Hide, 0f)
        assertEquals(.15f, FlexGlow.InitialResponse, 0f)
        assertEquals(.0015f, FlexGlow.TrackingResponse, 0f)
        assertEquals(1f, FlexGlow.SmoothingResponse, 0f)
        assertEquals(.75f, FlexGlow.ExtentScale, 0f)
        assertEquals(80f, FlexGlow.ExtentCap, 0f)
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
                assertEquals("local epoch follows its independent Show response", expected, body.glow.little, .09f)
            }
            assertEquals(1f, body.glow.big, 0f)
            val moved = Offset(1.25f, -.25f)
            repeat(fps) { frame ->
                step(body, fps, moved, 1f)
                assertTrue(body.glow.point.x in down.x..1.000001f)
                assertTrue(body.glow.point.y in -.000001f..down.y)
            }
            repeat(fps * 2) { frame ->
                step(body, fps, moved, 0f)
                val expected = 1 - critical((frame + 1.0) / fps, .5)
                assertEquals("hide fps=$fps frame=$frame", expected, body.glow.big, .02f)
            }
            assertEquals(0f, body.glow.big, 0f)
            assertTrue("local tail remains analytic until Float-zero", body.glow.little > 0f)
            repeat(fps * 10) { step(body, fps, moved, 0f) }
            assertEquals(0f, body.glow.little, 0f)
            assertTrue(body.settled)
        }
        println("critical show max absolute error=$worst bound=0.09")
    }

    @Test fun repressKeepsRetiringEnergyAndStartsAnIndependentFreshLocal() {
        for (releaseFrames in listOf(1, 4, 12, 30)) {
            val body = GlassMomentum()
            repeat(60) { step(body, 120, Offset(.2f, .7f), 1f) }
            repeat(releaseFrames) {
                step(body, 120, Offset(.2f, .7f), 0f)
                assertTrue(body.glow.big in 0f..1f && body.glow.little in 0f..1f)
            }
            val before = channels(body); val old = records(body).last()
            body.targetGlow(Offset(.8f, .3f), 1f, 1f)
            val after = channels(body)
            for (field in 0..1) assertEquals(before[0][field], after[0][field], 0f)
            assertSame(old, records(body).first())
            assertEquals(before[1][0], body.glow.retiring.single().little, 0f)
            assertEquals(0f, after[1][0], 0f); assertEquals(0f, after[1][1], 0f)
            assertEquals(Offset(.8f, .3f), body.glow.point)
            assertEquals(0f, after[2][1], 0f)
            assertEquals(0f, after[3][1], 0f)
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
            assertEquals(GlassShape(), lit.shape)
            repeat(fps * 10) { step(lit, fps, Offset(.5f, .5f), 0f, strength) }
            assertTrue(lit.settled)
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
        for (field in 0..2) assertEquals(left[0][field] * .75f + right[0][field] * .25f, actual[0][field], 0f)
        assertEquals(left[1][0] * .75f, joined.glow.retiring.single().little, 0f)
        assertEquals(right[1][0] * .25f, joined.glow.little, 0f)
        for (channel in 2..5) for (field in 0..2) assertEquals(right[channel][field], actual[channel][field], 0f)
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
        body.targetGlow(Offset(.8f, .3f), 1f, 0f)
        assertEquals(GlassGlow(), body.glow)
        body.targetGlow(null, 1f, 1f)
        assertEquals(GlassGlow(), body.glow)
    }

    @Test fun targetDropAndReleaseKeepSignedEnergyOutsideTheDrawableRange() {
        for (ms in 1..99) {
            val body = GlassMomentum()
            body.targetGlow(Offset(.5f, .5f), 1f, 1f)
            repeat(ms) { body.advance(0f, (.001f).toDouble(), contact = 1f); body.finishSettlement() }
            val before = channels(body)
            body.targetGlow(Offset(.5f, .5f), 0f, 1f)
            val after = channels(body)
            for (channel in 0..1) for (field in 0..1) assertEquals(before[channel][field], after[channel][field], 0f)
            repeat(12000) {
                body.advance(0f, (.001f).toDouble())
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
            repeat(1440) {
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

    @Test fun fluidLensUsesThePreparedShortDimensionInEachDirection() {
        val spec = (uiSpec(
            InterfaceSettings(popupMotion = PopupMotionStyle.Fluid),
            null,
            ExpansionGeometryDomain(1000f, 4000f, 1f / 30),
        ) as SpecResult.Accepted).spec
        EngineHarness(spec = spec).use { harness ->
            fun advanceFrames() = repeat(240) { harness.advance(1.0 / 120) }
            val motion = PopupWindowMotion(harness.clock, spec.expansion, "fluid-lens", false, false, PopupKind.Menu)
            try {
                motion.show(true)
                motion.place(Rect(40f, 50f, 90f, 130f), Rect(10f, 20f, 190f, 620f), density = 2f)
                motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
                motion.hold()
                advanceFrames()
                assertEquals(27f, motion.fluidLensHeight, 0f)
                assertEquals(-27.0, motion.fluidLensAmount, 0.0)

                motion.release()
                advanceFrames()
                assertEquals(0f, motion.fluidLensHeight, 0f)
                assertEquals(-0.001, motion.fluidLensAmount, 0.0)

                motion.show(false)
                motion.hold()
                advanceFrames()
                assertEquals(7.5f, motion.fluidLensHeight, 0f)
                assertEquals(-7.5, motion.fluidLensAmount, 0.0)

                motion.release()
                advanceFrames()
                motion.show(true)
                repeat(3) { harness.advance(1.0 / 120) }
                val openingHeight = motion.fluidLensHeight
                val openingAmount = motion.fluidLensAmount
                assertTrue(openingHeight > 0f && openingHeight < 27f)
                assertTrue(openingAmount < -0.001 && openingAmount > -27.0)
                motion.show(false)
                assertEquals(openingHeight, motion.fluidLensHeight, 0f)
                assertEquals(openingAmount, motion.fluidLensAmount, 0.0)
                advanceFrames()
                assertEquals(0f, motion.fluidLensHeight, 0f)
                assertEquals(-0.001, motion.fluidLensAmount, 0.0)
            } finally {
                motion.dispose()
            }
        }
    }

    @Test fun frozenOpticsMatchTheAcceptedSliceOneSource() {
        val root = File("src/main/java/io/github/anastaci1a/localica/ui")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val lens = File(root, "LensRenderer.kt").readText()
        var stock = lens
        fun replaceExact(before: String, after: String = "") {
            val start = stock.indexOf(before)
            check(start >= 0 && stock.indexOf(before, start + before.length) == -1) {
                "Expected one renderer source span starting with ${before.lineSequence().first()}"
            }
            stock = stock.replaceRange(start, start + before.length, after)
        }
        replaceExact("""
            import androidx.compose.ui.unit.IntSize
            import io.github.anastaci1a.localica.ui.spec.FluidContentBlurIn
            import io.github.anastaci1a.localica.ui.spec.FluidGaussianSpec
            import io.github.anastaci1a.localica.ui.spec.FluidLensingSpec
        """.trimIndent() + "\n")
        replaceExact("""
            |        uniform float4 mapShape;
            |        uniform float2 mapAngle;
            |        uniform float4 warp;
            |        uniform float2 warpClamp;
            |        uniform float warpMode;
        """.trimMargin() + "\n")
        replaceExact("""
            |        // Popup content warp: the glass map (distance below zero inside, outward unit normal,
            |        // full coverage) evaluated inline, then the displacement sample it masks.
            |        half4 warped(float2 p, float3 f) {
            |            float2 n = dot(f.yz, f.yz) > 0.0 ? -normalize(f.yz) : float2(0);
            |            float u = f.x - mapShape.z;
            |            float z = u - mapShape.w;
            |            float w = max(abs(n.x) + abs(n.y), 0.0001);
            |            float mask = z < -5.0 ? 0.0 : clamp(z / w + 0.5, 0.0, 1.0);
            |            if (mask < 0.00392156886) return half4(0);
            |            float r = clamp(u / mapShape.x, 0.0, 1.0);
            |            float lift = mix(r < 1.0 ? 1.0 - 0.2929 : 1.0, sqrt(1.0 - (1.0 - r) * (1.0 - r)), mapShape.y);
            |            float2 normal = float2(dot(n, float2(mapAngle.x, -mapAngle.y)), dot(n, mapAngle.yx));
            |            float2 m = (1.0 - lift) * normal * clamp((mapShape.x - u) / w + 0.5, 0.0, 1.0);
            |            return scene.eval(min(warpClamp, p + float2(dot(m, warp.xy), dot(m, warp.zw)))) * half(mask);
            |        }
        """.trimMargin() + "\n")
        replaceExact("            if (warpMode > 0.5) return warped(p, f);\n")
        replaceExact(", warp: LensWarp? = null")
        replaceExact("        val words = warp?.uniforms(unit)\n")
        replaceExact(" + (words ?: FloatArray(0))")
        replaceExact("""
            |        val w = words ?: FloatArray(12)
            |        shader.setFloatUniform("mapShape", w[0], w[1], w[2], w[3])
            |        shader.setFloatUniform("mapAngle", w[4], w[5])
            |        shader.setFloatUniform("warp", w[6], w[7], w[8], w[9])
            |        shader.setFloatUniform("warpClamp", w[10], w[11])
            |        shader.setFloatUniform("warpMode", if (warp == null) 0f else 1f)
        """.trimMargin() + "\n")
        // LensChainTest pins filter order and identity elision in the trailing lensChain adapter.
        val fluidTail = "\n/** Popup content warp inputs:"
        val tailStart = stock.indexOf(fluidTail)
        check(tailStart >= 0 && stock.indexOf(fluidTail, tailStart + fluidTail.length) == -1)
        stock = stock.substring(0, tailStart)
        // Zero fill makes identity exactly the complement of the accepted positive-radius test.
        replaceExact(
            "if (FluidGaussianSpec.isIdentity(blur.toDouble())) null\n" +
                "                else android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)",
            "if (blur > 0f) android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null",
        )
        // LensChainTest covers the installed blur order and identity elision.
        replaceExact(
            "lensChain(blurEffect, lens, android.graphics.RenderEffect::createChainEffect)",
            "(blurEffect?.let { android.graphics.RenderEffect.createChainEffect(lens, it) } ?: lens)",
        )
        assertEquals("3bbe5235adf8e87f0b047d0dfc35a4d0a247fb801d5a2b77a58d1541e74ecb91", sha(stock.toByteArray()))
        val source = File(root, "LiquidGlass.kt").readText()
        val start = source.indexOf("if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(")
        val effect = source.substring(start, source.indexOf("\n                else if", start))
        // Re-pinned for #748: depth/power/tone read the one material record and the D13/RM01 white-tint fade is deleted; kernel unchanged.
        assertEquals("25bd1e042f1820f425790952550ca323046beb263de21d74875f9b12da954b7e", sha(effect.toByteArray()))
    }
}
