package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassReadabilityTest {
    @get:Rule val compose = createComposeRule()

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
            val expected = (0..4).map { GlassTone(amount, 2f, light).channel(it / 4f) }
            compose.waitUntil(10000) { ramp().zip(expected).all { (a, b) -> kotlin.math.abs(a - b) < .025f } }
            val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap()
            assertEquals("The foreground is outside the tone map", 1f, ink[ink.width / 2, ink.height / 2].red, .01f)
        }
    }
}
