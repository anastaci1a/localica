package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(257.dp, 129.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Black)
                    for (x in 0 until 257) if (x % 32 < 8) drawRect(Color.White, Offset(x.toFloat(), 0f), Size(1f, size.height))
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
        }
    }
}
