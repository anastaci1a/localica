package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlassShadowRasterTest {
    @Test(timeout = 30000) fun directAndRetainedPrimitiveAndPathKeepTheDownwardMass() = runBlocking {
        val failures = mutableListOf<String>()
        for (d in listOf(2f, 2.48125f, 3f)) for (generic in listOf(false, true)) {
            val width = (248 * d).toInt(); val height = (224 * d).toInt()
            val body = RoundRect(72 * d, 88 * d, 176 * d, 136 * d, CornerRadius(24 * d))
            val path = Path().apply { addRoundRect(body) }
            val shadow = RetainedGlassShadow(this@runBlocking)
            fun frame(retained: Boolean): ImageBitmap = ImageBitmap(width, height).also { image ->
                CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                    if (retained) with(shadow) {
                        if (generic) draw(path, Offset.Zero, 1f, 1f) else draw(listOf(body to 1f), 1f, 1f)
                    } else glassShadows(if (generic) emptyList() else listOf(body to 1f), 1f, 1f,
                        GlassShadowGeometry(), path = if (generic) path else null)
                }
            }
            val reference = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMassReference(
                width, height, body.left, body.top, body.right, body.bottom, 24 * d, d)
            val left = kotlin.math.ceil(body.left).toInt()
            val columns = kotlin.math.floor(body.right).toInt() - left
            val nearReference = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMassReference(
                columns, height, body.left - left, body.top, body.right - left, body.bottom, 24 * d, d, bandDp = 16f)
            fun verify(image: ImageBitmap, mode: String) {
                val pixels = image.toPixelMap()
                val mass = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMass(
                    width, height, body.top, body.bottom) { x, y -> pixels[x, y].alpha.toDouble() }
                if (!reference.accepts(mass)) failures += "d=$d generic=$generic $mode mass=$mass reference=$reference"
                val near = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMass(
                    columns, height, body.top, body.bottom, (16 * d).toInt()) { x, y -> pixels[left + x, y].alpha.toDouble() }
                if (!nearReference.accepts(near)) failures += "d=$d generic=$generic $mode near mass=$near reference=$nearReference"
            }
            for (image in listOf(frame(false), frame(true))) verify(image, "live")
            coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(1, shadow.rasterizations)
            verify(frame(true), "retained")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    @Test(timeout = 10000) fun rasterIgnoresLiveNeighborExclusion() = runBlocking {
        val bodies = listOf(RoundRect(40f, 30f, 120f, 80f, CornerRadius(16f)) to 1f)
        val size = Size(200f, 140f)
        suspend fun prepared(exclusion: GlassShadowExclusion?): IntArray {
            val shadow = RetainedGlassShadow(this@runBlocking)
            fun frame(current: GlassShadowExclusion?): ImageBitmap = ImageBitmap(200, 140).also { image ->
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
                    with(shadow) { draw(bodies, 1f, 1f, exclusion = current) }
                }
            }
            frame(exclusion)
            coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(1, shadow.rasterizations)
            val image = frame(null).asAndroidBitmap()
            return IntArray(200 * 140).also { image.getPixels(it, 0, 200, 0, 0, 200, 140) }
        }
        val control = prepared(null)
        val excluded = prepared(GlassShadowExclusion(bodies.map { it.first }, Offset.Zero, size, io.github.anastaci1a.localica.ui.GlassBodyTransform(), 0f))
        assertTrue("The control must contain an exterior shadow", control.any { it ushr 24 > 0 })
        assertArrayEquals("A raster prepared near a neighbor must replay the complete field away from home", control, excluded)
    }
}
