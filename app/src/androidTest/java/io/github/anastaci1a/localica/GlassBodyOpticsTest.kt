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
    val shape get() = GlassShape(strain, depth, scale)
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
                fun DrawScope.backing() {
                drawRect(Color(0xff405060))
                if (background > 0) for (y in 0 until 96 step 4) for (x in 0 until 112 step 4)
                    drawRect(if (background == 1) { if ((x + y) % 8 == 0) Color.White else Color.Black }
                        else Color(0xff000000.toInt() or ((x * 23 + y * 47) and 0xffffff)), Offset(x.toFloat(), y.toFloat()), Size(4f, 4f))
                }
                backing()
                val bounds = Rect(.25f, .5f, 111f, 95f)
                val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(19.5f))) }
                clipPath(path) {
                    drawTouchLightFixture(glow, if (frozen) original.shape else shape, true, GlassRimCache(), bounds) { backing() }
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

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun normalAndSingleToneKernelPixelsMatchIndependentFrozenRendererAtMatchedInputs() {
        val candidate = LensRenderer()
        val baseline = FrozenS1LensRenderer()
        val bitmap = Bitmap.createBitmap(112, 96, Bitmap.Config.ARGB_8888)
        try {
            for ((index, depth) in cases.withIndex()) for (singleTone in listOf(false, true)) for (background in 0..2) {
                val gain = if (index % 2 == 0) null else -(16f / 64f) / .24f
                val actual = supplied(depth, gain)
                val original = S1OpticalState(.037f, depth, gain)
                val pixels = IntArray(112 * 96) { i ->
                    if (singleTone || background == 0) 0xff405060.toInt()
                    else if (background == 1) { if ((i / 112 / 4 + i % 112 / 4) % 2 == 0) -1 else 0xff000000.toInt() }
                    else 0xff000000.toInt() or ((i * 7919) and 0xffffff)
                }
                bitmap.setPixels(pixels, 0, 112, 0, 0, 112, 96)
                val geometry = LensGeometry(Rect(.25f, .5f, 111.5f, 95.25f), 19.5f)
                val thickness = (if (index % 2 == 0) 1f else actual.thickness) * actual.thickness
                val frozenThickness = (if (index % 2 == 0) 1f else original.thickness) * original.thickness
                val illumination = if (singleTone) 0f else 1f
                val tone = if (index % 2 == 0) GlassTone() else GlassTone(.3f, 1.4f, true)
                val expected = baseline.effect(geometry, 1.75f, .8f * frozenThickness, power = .75f,
                    whiteTint = .2f, illumination = illumination, flattening = io.github.anastaci1a.localica.ui.engine.glassBodyFlattening(frozenThickness), tone = tone)
                val observed = candidate.effect(geometry, 1.75f, .8f * thickness, power = .75f,
                    whiteTint = .2f, illumination = illumination, flattening = io.github.anastaci1a.localica.ui.engine.glassBodyFlattening(thickness), tone = tone)
                assertArrayEquals("Normal/SingleTone state=$index singleTone=$singleTone background=$background", renderS1Kernel(bitmap, expected), renderS1Kernel(bitmap, observed))
            }
        } finally { bitmap.recycle() }
    }
}

private fun renderS1Kernel(bitmap: Bitmap, effect: RenderEffect): IntArray {
    val node = RenderNode("S1 matched optics").apply { setPosition(0, 0, 112, 96) }
    try {
        node.beginRecording().drawBitmap(bitmap, 0f, 0f, null)
        node.endRecording()
        node.setRenderEffect(effect.asAndroidRenderEffect())
        val image = renderNativeReferenceImage(112, 96, "S1 matched optical readback") { it.drawRenderNode(node) }
        try {
            return IntArray(112 * 96).also { image.asAndroidBitmap().getPixels(it, 0, 112, 0, 0, 112, 96) }
        } finally { image.asAndroidBitmap().recycle() }
    } finally { node.discardDisplayList() }
}
