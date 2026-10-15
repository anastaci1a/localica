package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Additional edge/layer controls; the original 3/4-body diagnostic is unchanged. */
class MixedShadowLayerTest {
    private val background = Color(.8f, .6f, .4f)
    private fun render(bodies: List<Pair<RoundRect, Float>>, density: Float, backing: Color = background): PixelMap {
        val image = ImageBitmap(480, 320)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(480f, 320f)) {
            drawRect(backing)
            glassShadows(bodies, 1f, .6f, GlassShadowGeometry())
        }
        return image.toPixelMap()
    }

    @Test fun absoluteFieldPreservesFractionalEdgesAndColoredBacking() {
        val failures = mutableListOf<String>()
        for (density in listOf(1f, 1.3f, 2.625f)) for (step in listOf(0f, .125f, .5f, 47.375f)) {
            val bodies = listOf(1f, .18126415f, .43256444f, .21986524f).mapIndexed { index, opacity ->
                RoundRect(70.375f + index * step, 80.625f, 220.375f + index * step, 180.625f,
                    CornerRadius(31.25f)) to opacity
            }
            val singles = bodies.map { render(listOf(it), density) }
            val actual = render(bodies, density)
            var maximum = 0f
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                // Global body cutouts intentionally differ from independent singles.
                // Exclude their bounding boxes; shadow AA boundaries remain sampled.
                if (bodies.any { it.first.boundingRect.inflate(1f).contains(Offset(x + .5f, y + .5f)) }) continue
                val value = actual[x, y]
                val expected = listOf(singles.minOf { it[x, y].red }, singles.minOf { it[x, y].green }, singles.minOf { it[x, y].blue })
                maximum = maxOf(maximum, abs(value.red - expected[0]), abs(value.green - expected[1]), abs(value.blue - expected[2]))
                assertEquals("Layer cannot overwrite opaque backing", 1f, value.alpha, 0f)
            }
            Log.i("MixedShadowLayer", "density=$density step=$step maxError=$maximum")
            if (maximum > 5f / 255f) failures += "$density/$step:$maximum"
            assertEquals("Layer bounds leave distant backing unchanged", background.toArgb(), actual[479, 319].toArgb())
        }
        assertTrue("Original 5/255 field budget: $failures", failures.isEmpty())
    }

    @Test fun isolatedLayerLeavesTransparentPixelsAndInteriorUntouched() {
        val bodies = listOf(.5f, .50000006f, .001001f).map { opacity ->
            RoundRect(120.25f, 90.75f, 280.25f, 190.75f, CornerRadius(30f)) to opacity
        }
        val pixels = render(bodies, 1.3f, Color.Transparent)
        assertEquals(0f, pixels[10, 10].alpha, 0f)
        assertEquals(0f, pixels[200, 140].alpha, 0f)
        assertTrue("Mixed shadow remains visible outside the cutout", pixels[200, 198].alpha > .01f)
    }
}
