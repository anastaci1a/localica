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
