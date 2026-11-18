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

    @Test fun pageInkContrastsWithTheCompositedFace() {
        var lightInk by mutableStateOf(false)
        var page by mutableStateOf(Color.White)
        var content by mutableStateOf(Color.White)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, shadowStrength = 0f)) {
                Box(Modifier.fillMaxSize().background(page)) {
                    val source = rememberFrostedBackdrop(Unit, dark = lightInk)
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(content) }
                    LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(96.dp).testTag("face"), source, refraction = 0f) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(10.dp)
                            .background(androidx.compose.material3.LocalContentColor.current).testTag("ink"))
                    }
                }
            }
        } }
        // Each case: page polarity, page background, recorded content, composited face red, minimum contrast. Light faces take
        // the rows (black .7001953, white clipped to 1); light-ink pages keep the graded face (.25 compression, .06 white tint).
        // A translucent face composites over the displayed content, itself over the page background.
        val cases = listOf(
            listOf(false, Color.White, Color.White, 1f, 7f),
            listOf(true, Color.Black, Color.White, .75f, 1.7f),
            listOf(false, Color.White, Color.Black.copy(alpha = .25f), .25f * .7001953f + .75f * .75f, 4.5f),
            listOf(true, Color.Black, Color.Black.copy(alpha = .25f), .25f * .0591f, 4.5f),
            listOf(false, Color.White, Color.Black, .7001953f, 4.5f))
        for ((light, background, recorded, faceRed, minimum) in cases) {
            compose.runOnIdle { lightInk = light as Boolean; page = background as Color; content = recorded as Color }
            fun state(): Triple<Color, Color, Float> {
                val face = compose.onNodeWithTag("face").captureToImage().toPixelMap().let { it[it.width / 2, it.height * 3 / 4] }
                val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                return Triple(face, ink, (maxOf(face.luminance(), ink.luminance()) + .05f) / (minOf(face.luminance(), ink.luminance()) + .05f))
            }
            compose.waitUntil(10000) { state().let { (face, ink, ratio) ->
                kotlin.math.abs(face.red - faceRed as Float) < .03f && (ink.luminance() > .5f) == light && ratio >= minimum as Float } }
        }
    }
}
