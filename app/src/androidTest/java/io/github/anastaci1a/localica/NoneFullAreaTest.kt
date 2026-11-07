package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoneFullAreaTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun oddFootprintIncludesEveryPixelInsteadOfSixtyFourPointSamples() {
        lateinit var source: FrostedBackdrop
        var whiteWidth by mutableIntStateOf(8)
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            // The source extends past the surface by more than its blur reach, so the whole
            // refraction preimage lies over recorded pixels.
            Box(Modifier.size(297.dp, 169.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Black)
                    for (x in -20 until 277) if (Math.floorMod(x, 32) < whiteWidth)
                        drawRect(Color.White, Offset(x + 20f, 0f), Size(1f, size.height))
                }
                LiquidGlassSurface(Modifier.offset(20.dp, 20.dp).size(257.dp, 129.dp).testTag("mean"), source,
                    cornerRadius = 0.dp, pressFeedback = false, shadow = false)
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val image = compose.onNodeWithTag("mean").captureToImage().toPixelMap()
        // The averaged region is the shared refraction preimage (#624), not a re-derived footprint.
        val region = lensRefractionPreimage(Rect(0f, 0f, 257f, 129f), liquidGlassPreimageOptics(Size(257f, 129f), 0f,
            GlassAppearance().depth, 1f, 1f, 22f * GlassAppearance().blur, 32f))
        assertEquals("Preimage $region stays inside the source", region, Rect(-20f, -20f, 277f, 149f).intersect(region))
        // Independent oracle: the covered fraction of every one-pixel white column, all rows alike.
        fun mean(width: Int) = (-20 until 277).filter { Math.floorMod(it, 32) < width }.sumOf { x ->
            (minOf(region.right, x + 1f) - maxOf(region.left, x.toFloat())).coerceAtLeast(0f).toDouble() }.toFloat() / region.width
        val expected = mean(8)
        for (x in listOf(image.width / 4, image.width / 2, image.width * 3 / 4)) {
            val actual = image[x, image.height / 2]
            assertEquals("Full 257x129 area mean at x=$x", expected, actual.red, .02f)
            assertEquals(expected, actual.green, .02f)
            assertEquals(expected, actual.blue, .02f)
            if (Build.VERSION.SDK_INT < 33) assertLegacyAreaBound(actual.red, expected,
                region.translate(20f, 20f), 512, 256)
        }
        // Keep the same surface mounted while its backdrop changes. A retained
        // reduction must refresh its input rather than freeze the first mean.
        for (width in listOf(16, 24, 8)) {
            compose.runOnIdle { whiteWidth = width }
            val nextExpected = mean(width)
            compose.waitUntil(15_000) {
                val pixels = compose.onNodeWithTag("mean").captureToImage().toPixelMap()
                listOf(pixels.width / 4, pixels.width / 2, pixels.width * 3 / 4).all { x ->
                    val pixel = pixels[x, pixels.height / 2]
                    listOf(pixel.red, pixel.green, pixel.blue).all { kotlin.math.abs(it - nextExpected) < .02f }
                }
            }
        }
    }
}

/** Independent geometric bound: count the covered area of every cut boundary cell.
 * Both native fixtures contain black and white boundary pixels, so contrast is one. */
internal fun assertLegacyAreaBound(actual: Float, expected: Float, footprint: Rect,
    paddedWidth: Int, paddedHeight: Int) {
    val cellWidth = paddedWidth / 32f
    val cellHeight = paddedHeight / 32f
    var boundaryArea = 0f
    for (y in 0 until 32) for (x in 0 until 32) {
        val cell = Rect(x * cellWidth, y * cellHeight, (x + 1) * cellWidth, (y + 1) * cellHeight)
        val overlap = cell.intersect(footprint)
        if (!overlap.isEmpty && overlap.width * overlap.height < cellWidth * cellHeight)
            boundaryArea += overlap.width * overlap.height
    }
    val fraction = boundaryArea / (footprint.width * footprint.height)
    assertTrue("Legacy mean error ${kotlin.math.abs(actual - expected)} exceeds boundary-area fraction $fraction × contrast 1",
        kotlin.math.abs(actual - expected) <= fraction)
}
