package io.github.anastaci1a.localica

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preferencesPersistIndependentlyAndClampInvalidValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = InterfacePreferences(context)
        val original = prefs.read()
        try {
            val glass = GlassAppearance(.73f, .18f, .04f, .91f, .67f, TabLensStyle.Overlay, .81f, .23f, 3f, 1.7f)
            prefs.write(original.copy(glass = glass))
            assertEquals(glass, InterfacePreferences(context).read().glass)
            prefs.write(original.copy(glass = GlassAppearance(-1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)))
            assertEquals(GlassAppearance(0f, 1f, .12f, .6f), prefs.read().glass)
            prefs.write(original.copy(glass = GlassAppearance()))
            assertEquals(GlassAppearance(), prefs.read().glass)
        } finally { prefs.write(original) }
    }

    @Test fun blurTintAndShadowChangeIndependentlyWhileForegroundStaysSharp() {
        var appearance by mutableStateOf(GlassAppearance(0f, 0f, 0f, 0f, contrast = 0f))
        var expansion by mutableFloatStateOf(0f)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides appearance) {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("scene")) {
                    source = rememberFrostedBackdrop(Unit, dark = true)
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color.Red, size = Size(size.width / 2, size.height))
                        drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                    }
                    LiquidGlassSurface(Modifier.align(Alignment.Center).size(280.dp, 96.dp).testTag("glass"), source, frostProgress = expansion) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(10.dp).background(Color.Black).testTag("ink"))
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(10.dp)
                            .background(androidx.compose.material3.LocalContentColor.current).testTag("adaptive-ink"))
                    }
                }
            }
        } }
        fun nearBoundary(): Color {
            val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
            return pixels[pixels.width / 2 - 5, pixels.height / 2]
        }
        val clear = nearBoundary()
        assertTrue("Zero blur preserves the red side: $clear", clear.red > .95f && clear.blue < .05f)
        compose.runOnIdle { expansion = 1f }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        compose.waitUntil(5000) { nearBoundary().blue > .2f }
        compose.runOnIdle { expansion = 0f }
        compose.waitUntil(5000) { nearBoundary().blue < .05f }
        compose.runOnIdle { appearance = appearance.copy(blur = 1f) }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.glassFallback != null }
        compose.waitUntil(5000) { nearBoundary().blue > .2f }
        val blurred = nearBoundary()
        assertTrue("Frost scatters both sides: $blurred", blurred.red > .2f && blurred.blue > .2f)
        compose.runOnIdle { appearance = appearance.copy(whiteTint = .5f) }
        val tinted = nearBoundary()
        assertTrue("White tint is independent of scattering: $tinted", tinted.green > .45f)
        val adaptive = compose.onNodeWithTag("adaptive-ink").captureToImage().toPixelMap()
        val contrasting = adaptive[adaptive.width / 2, adaptive.height / 4]
        assertTrue("Heavy white tint needs dark foreground contrast: $contrasting", contrasting.red < .2f)
        val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap()
        val black = ink[ink.width / 2, ink.height * 3 / 4]
        assertTrue("Foreground is never blurred or tinted: $black", black.red + black.green + black.blue < .03f)
        compose.runOnIdle { appearance = GlassAppearance(0f, 0f, 1f, 1f, contrast = 0f) }
        assertTrue("Shadow cannot darken the clear center", nearBoundary().red > .95f)
        fun outsideBrightness(): Float {
            val scene = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
            val glass = compose.onNodeWithTag("glass").captureToImage()
            val pixel = scene[scene.width / 2 + 60, scene.height / 2 + glass.height / 2 + 20]
            return pixel.red + pixel.green + pixel.blue
        }
        val broad = outsideBrightness()
        compose.runOnIdle { appearance = appearance.copy(shadowSpread = 0f) }
        val tight = outsideBrightness()
        assertTrue("Spread extends the shadow without tinting its center: $broad vs $tight", tight > broad + .02f)
        compose.runOnIdle { appearance = appearance.copy(shadowSpread = 1f, shadowStrength = 0f) }
        val off = outsideBrightness()
        assertTrue("Zero strength removes even a broad shadow: $off", off > .98f)
    }
}
