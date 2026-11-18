package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassReadabilityTest {
    @get:Rule val compose = createComposeRule()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun entireGlassUsesTheBoundedCurveWithoutTintingItsForeground() {
        var lightInk by mutableStateOf(false)
        var trim by mutableFloatStateOf(.25f)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f,
                shadowStrength = 0f, contrast = trim)) {
                Box(Modifier.fillMaxSize()) {
                    val source = rememberFrostedBackdrop(Unit, dark = lightInk)
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        repeat(5) { i -> drawRect(Color(i / 4f, i / 4f, i / 4f),
                            Offset(size.width * i / 5, 0f), Size(size.width / 5, size.height)) }
                    }
                    LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(96.dp).testTag("tone"), source, refraction = 0f) {
                        Canvas(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(10.dp).testTag("ink")) { drawRect(Color.White) }
                    }
                }
            }
        } }
        fun ramp(): List<Float> {
            val pixels = compose.onNodeWithTag("tone").captureToImage().toPixelMap()
            return (0..4).map { pixels[(pixels.width * (it + .5f) / 5).toInt(), pixels.height / 2].red }
        }
        for (light in listOf(false, true)) for (amount in listOf(.25f, .5f, 0f)) {
            compose.runOnIdle { lightInk = light; trim = amount }
            val expected = (0..4).map { if (light) GlassTone(amount, 2f, true).channel(it / 4f) else minOf(1f, (.5400391f - .2017822f - .0203094f) * (it / 4f) + .7001953f) }
            compose.waitUntil(10000) { ramp().zip(expected).all { (a, b) -> kotlin.math.abs(a - b) < .025f } }
            val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap()
            assertEquals("The foreground is outside the tone map", 1f, ink[ink.width / 2, ink.height / 2].red, .01f)
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun pageInkContrastsWithTheFaceOverAWhiteBackdrop() {
        var lightInk by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, shadowStrength = 0f)) {
                Box(Modifier.fillMaxSize()) {
                    val source = rememberFrostedBackdrop(Unit, dark = lightInk)
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                    LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(96.dp).testTag("face"), source, refraction = 0f) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(10.dp)
                            .background(androidx.compose.material3.LocalContentColor.current).testTag("ink"))
                    }
                }
            }
        } }
        // Light-appearance faces carry dark ink at 7:1; the light-ink face keeps its default .25 compression (white to .75).
        for ((light, minimum) in listOf(false to 7f, true to 1.7f)) {
            compose.runOnIdle { lightInk = light }
            fun contrast(): Pair<Float, Float> {
                val face = compose.onNodeWithTag("face").captureToImage().toPixelMap().let { it[it.width / 2, it.height * 3 / 4] }.luminance()
                val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }.luminance()
                return ink to (maxOf(face, ink) + .05f) / (minOf(face, ink) + .05f)
            }
            compose.waitUntil(10000) { contrast().let { (ink, ratio) -> (ink > .5f) == light && ratio >= minimum } }
        }
    }
}
