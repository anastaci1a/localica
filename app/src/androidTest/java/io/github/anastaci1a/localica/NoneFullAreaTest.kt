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
            Box(Modifier.size(257.dp, 129.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Black)
                    for (x in 0 until 257) if (x % 32 < whiteWidth) drawRect(Color.White, Offset(x.toFloat(), 0f), Size(1f, size.height))
                }
                LiquidGlassSurface(Modifier.fillMaxSize().testTag("mean"), source,
                    cornerRadius = 0.dp, pressFeedback = false, shadow = false)
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val image = compose.onNodeWithTag("mean").captureToImage().toPixelMap()
        // Independent oracle: eight white pixels per complete period, plus the odd white edge.
        val expected = 65f / 257f
        for (x in listOf(image.width / 4, image.width / 2, image.width * 3 / 4)) {
            val actual = image[x, image.height / 2]
            assertEquals("Full 257x129 area mean at x=$x", expected, actual.red, .02f)
            assertEquals(expected, actual.green, .02f)
            assertEquals(expected, actual.blue, .02f)
            if (Build.VERSION.SDK_INT < 33) assertLegacyAreaBound(actual.red, expected,
                Rect(0f, 0f, 257f, 129f), 512, 256)
        }
        // Keep the same surface mounted while its backdrop changes. A retained
        // reduction must refresh its input rather than freeze the first mean.
        for (width in listOf(16, 24, 8)) {
            compose.runOnIdle { whiteWidth = width }
            val nextExpected = (8 * width + 1) / 257f
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
