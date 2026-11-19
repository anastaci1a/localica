package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.RenderNode
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Independent Float input adapter; frozen cap/thickness arithmetic and supplied matched geometry. */
internal class S1OpticalState(val strain: Float, rawDepth: Float, gain: Float?) {
    val depth = .24f * tanh(rawDepth / .24f)
    val thickness = exp(depth)
    private val scale = gain?.let { 1f + it * rawDepth }
    fun scaleX(vertical: Boolean) = scale?.let { it * exp(if (vertical) -strain else strain) } ?: exp((if (vertical) -strain else strain) - depth / 2)
    fun scaleY(vertical: Boolean) = scale?.let { it * exp(if (vertical) strain else -strain) } ?: exp((if (vertical) strain else -strain) - depth / 2)
}

class GlassBodyOpticsTest {
    private val cases = listOf(0f, -.12f, -.24f, -.27f, .06f, 0f)
    private fun supplied(rawDepth: Float, gain: Float?): GlassShape {
        val body = GlassMomentum(gain?.let { FlexSpec.Small.copy(scaleGain = it) })
        for ((channel, fieldValue) in listOf("depth" to rawDepth, "pressure" to .037f)) {
            val spring = body.javaClass.getDeclaredField(channel).apply { isAccessible = true }.get(body)
            spring.javaClass.getDeclaredField("value").apply { isAccessible = true }.setFloat(spring, fieldValue)
        }
        return body.shape
    }
    private fun paint(shape: GlassShape, original: S1OpticalState, glow: GlassGlow, tone: GlassTone,
        density: Float, background: Int, frozen: Boolean): IntArray {
        val image = ImageBitmap(112, 96)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(111.5f, 95.25f)) {
                drawRect(Color(0xff405060))
                if (background > 0) for (y in 0 until 96 step 4) for (x in 0 until 112 step 4)
                    drawRect(if (background == 1) { if ((x + y) % 8 == 0) Color.White else Color.Black }
                        else Color(0xff000000.toInt() or ((x * 23 + y * 47) and 0xffffff)), Offset(x.toFloat(), y.toFloat()), Size(4f, 4f))
                val bounds = Rect(.25f, .5f, 111f, 95f)
                val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(19.5f))) }
                clipPath(path) {
                    if (frozen) drawFrozenS1Glow(glow, { original }, true, tone, FrozenS1RimCache())
                    else drawContactGlow(glow, { shape }, true, tone, GlassRimCache())
                    if (frozen) drawOriginalGlassRim(bounds, 19.5f, path, tone)
                    else drawGlassRim(bounds, 19.5f, path, tone, cache = GlassRimCache())
                }
            }
            return IntArray(112 * 96).also { image.asAndroidBitmap().getPixels(it, 0, 112, 0, 0, 112, 96) }
        } finally { image.asAndroidBitmap().recycle() }
    }

    @Test fun matchedStateBodyInputsGlowAndRimAreBitEqualAgainstTheFrozenBase() {
        for ((index, depth) in cases.withIndex()) for (gain in listOf(null, -(16f / 64f) / .24f, -(16f / 160f) / .24f)) {
            val shape = supplied(depth, gain)
            val original = S1OpticalState(.037f, depth, gain)
            assertEquals(original.depth.toBits(), shape.depth.toBits())
            assertEquals(original.thickness.toBits(), shape.thickness.toBits())
            for (vertical in listOf(false, true)) {
                assertEquals(original.scaleX(vertical).toBits(), shape.scaleX(vertical).toBits())
                assertEquals(original.scaleY(vertical).toBits(), shape.scaleY(vertical).toBits())
            }
            for (alpha in listOf(-.2f, 0f, .37f, 1f)) for (density in listOf(1f, 2.5f)) for (background in 0..2) {
                val glow = GlassGlow(alpha, alpha * .3f, Offset(if (index % 2 == 0) .25f else 1.25f, -.25f))
                val tone = if (index % 2 == 0) GlassTone() else GlassTone(.3f, 1.4f, true)
                assertArrayEquals("state=$index gain=$gain alpha=$alpha density=$density background=$background",
                    paint(shape, original, glow, tone, density, background, true), paint(shape, original, glow, tone, density, background, false))
            }
        }
    }

    /** Each backdrop pixel encodes its own coordinates, so a sample reads back where the kernel looked. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun kernelSamplesTheSharedRefractionLookups() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(128 * 128) { i -> 0xff000000.toInt() or ((i % 128 * 2) shl 16) or ((i / 128 * 2) shl 8) }, 0, 128, 0, 0, 128, 128)
            for (unit in listOf(1f, 1.25f)) for (corner in listOf(24f, 12f)) {
                val geometry = LensGeometry(Rect(20f, 20f, 108f, 68f), corner)
                // A light-ink tone with no trim maps colour unchanged, so the coordinates read back as encoded.
                val pixels = renderS1Kernel(bitmap, LensRenderer().effect(geometry, 0f, 1.35f, unit, illumination = 0f,
                    tone = GlassTone(lightInk = true)), 128, 128)
                var checked = 0
                for (y in 20 until 68) for (x in 20 until 108) {
                    val p = Offset(x + .5f, y + .5f)
                    val field = geometry.field(p)
                    if (field.distance < 0f) continue
                    val lookup = GlassRefractionReference.at(field, unit)
                    val inner = p + lookup.inner
                    val outer = p + lookup.outer
                    if (listOf(inner, outer).any { it.x !in 1f..126f || it.y !in 1f..126f }) continue
                    val expected = inner + (outer - inner) * lookup.outerWeight
                    val color = pixels[y * 128 + x]
                    val where = "unit=$unit corner=$corner ($x,$y) distance=${field.distance}"
                    assertEquals(where, expected.x, (color shr 16 and 255) / 2f + .5f, 1f)
                    assertEquals(where, expected.y, (color shr 8 and 255) / 2f + .5f, 1f)
                    checked++
                }
                assertTrue(checked > 3000)
            }
        } finally { bitmap.recycle() }
    }

    /** The highlight follows the shape's continuous-corner normal. A uniform backdrop makes every
     * lookup equal; the face colour comes from the same kernel without illumination, so only the
     * highlight terms are asserted. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun kernelHighlightFollowsTheShapeNormal() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(89, 128, 166))
            val tone = GlassTone(trim = .3f)
            val depth = 1.35f; val flattening = .5f; val whiteTint = .1f; val power = .7f
            for (corner in listOf(24f, 12f, 0f)) {
                val geometry = LensGeometry(Rect(20f, 20f, 108f, 68f), corner)
                fun render(illumination: Float) = renderS1Kernel(bitmap, LensRenderer().effect(geometry, 0f, depth, 1f,
                    power = power, whiteTint = whiteTint, illumination = illumination, flattening = flattening, tone = tone), 128, 128)
                val faces = render(0f)
                val pixels = render(1f)
                var checked = 0
                for (y in 20 until 68) for (x in 20 until 108) {
                    val field = geometry.field(Offset(x + .5f, y + .5f))
                    if (field.distance < 0f) continue
                    val face = faces[y * 128 + x].let { floatArrayOf((it shr 16 and 255) / 255f, (it shr 8 and 255) / 255f, (it and 255) / 255f) }
                    val expected = referenceHighlight(face, field, geometry.span, depth, flattening, tone, power)
                    val color = pixels[y * 128 + x]
                    val where = "corner=$corner ($x,$y) distance=${field.distance}"
                    assertEquals(where, expected[0], (color shr 16 and 255) / 255f, 3 / 255f)
                    assertEquals(where, expected[1], (color shr 8 and 255) / 255f, 3 / 255f)
                    assertEquals(where, expected[2], (color and 255) / 255f, 3 / 255f)
                    checked++
                }
                assertTrue(checked > 3000)
            }
        } finally { bitmap.recycle() }
    }
}

private fun renderS1Kernel(bitmap: Bitmap, effect: RenderEffect, width: Int = 112, height: Int = 96): IntArray {
    val node = RenderNode("S1 matched optics").apply { setPosition(0, 0, width, height) }
    try {
        node.beginRecording().drawBitmap(bitmap, 0f, 0f, null)
        node.endRecording()
        node.setRenderEffect(effect.asAndroidRenderEffect())
        val image = renderNativeReferenceImage(width, height, "S1 matched optical readback") { it.drawRenderNode(node) }
        try {
            return IntArray(width * height).also { image.asAndroidBitmap().getPixels(it, 0, width, 0, 0, width, height) }
        } finally { image.asAndroidBitmap().recycle() }
    } finally { node.discardDisplayList() }
}

/** Kotlin transcription of the kernel's highlight over an opaque face colour. */
private fun referenceHighlight(color: FloatArray, field: LensField, span: Float, depth: Float, flattening: Float,
    tone: GlassTone, power: Float): FloatArray {
    val trim = if (tone.trim * tone.weight > 1f) 1f / tone.weight else tone.trim
    val mixLight = tone.lightMix.coerceIn(0f, 1f)
    val u = (1f - kotlin.math.max(field.distance, 0f) / kotlin.math.max(span, .001f)).coerceIn(0f, 1f)
    val root = kotlin.math.sqrt(kotlin.math.max(1f - u * u, .0004f))
    val crown = .62f * depth / kotlin.math.sqrt(1f + depth * depth / 4f)
    val shoulder = .4f + .06f * flattening.coerceIn(0f, 1f)
    val slope = crown * u / root * (1f - 2f * shoulder + 3f * shoulder * u * u)
    val nx = -field.inward.x * slope; val ny = -field.inward.y * slope
    val length = kotlin.math.sqrt(nx * nx + ny * ny + 1f)
    val nz = 1f / length
    val light = kotlin.math.sqrt(.45f * .45f + .75f * .75f + .6f * .6f)
    val facing = (nx * -.45f + ny * -.75f + .6f) / length / light
    val grazing = (1f - nz).let { it * it * it }
    val reflection = (.018f * (1f - nz) + .26f * grazing) * power
    val white = 1f + (1f - trim - 1f) * mixLight
    val black = trim + (0f - trim) * mixLight
    return FloatArray(3) { i ->
        val lit = color[i] + (white - color[i]) * reflection * kotlin.math.max(facing, 0f)
        lit + (black - lit) * reflection * kotlin.math.max(-facing, 0f) * .3f
    }
}
