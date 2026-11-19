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
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun frostedPanelsStillScatterTheParentScene() {
        var top by mutableStateOf(300.dp)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(240.dp).testTag("frost-fixture")) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val cell = 4.dp.toPx()
                    for (x in 0..(size.width / cell).toInt()) drawRect(if (x % 2 == 0) Color.Black else Color.White,
                        Offset(x * cell, 0f), Size(cell, size.height))
                }
                FrostedSurface(Modifier.offset(y = top).fillMaxSize(), source = source, shadow = false) {}
            }
        } }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        compose.runOnIdle { top = 0.dp }
        val pixels = compose.onNodeWithTag("frost-fixture").captureToImage().toPixelMap()
        var low = 1f; var high = 0f
        for (x in pixels.width / 4 until pixels.width * 3 / 4) {
            val color = pixels[x, pixels.height / 2].red
            low = minOf(low, color); high = maxOf(high, color)
        }
        assertTrue("Frost must scatter stripes, not show an unfiltered parent: $low..$high", high - low < .12f)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun defaultFrostKeepsForegroundSharpAndPageInk() {
        var expansion by mutableFloatStateOf(0f)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme {

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

        } }
        fun nearBoundary(): Color {
            val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
            return pixels[pixels.width / 2 - 5, pixels.height / 2]
        }
        compose.runOnIdle { expansion = 1f }
        compose.waitUntil(5000) { nearBoundary().blue > .2f }
        val blurred = nearBoundary()
        assertTrue("Frost scatters both sides: $blurred", blurred.red > .2f && blurred.blue > .2f)
        val adaptive = compose.onNodeWithTag("adaptive-ink").captureToImage().toPixelMap()
        val contrasting = adaptive[adaptive.width / 2, adaptive.height / 4]
        assertTrue("Default frost keeps this page’s white foreground: $contrasting", contrasting.red > .98f)
        val ink = compose.onNodeWithTag("ink").captureToImage().toPixelMap()
        val black = ink[ink.width / 2, ink.height * 3 / 4]
        assertTrue("Foreground is never blurred or tinted: $black", black.red + black.green + black.blue < .03f)
    }
}
