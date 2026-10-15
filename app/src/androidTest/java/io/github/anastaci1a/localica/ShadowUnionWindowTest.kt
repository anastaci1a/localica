package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Actual window Canvas overlap proof; same predeclared 5/255 raster budget. */
class ShadowUnionWindowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mixedWindowShadowsDoNotStackInTheirOverlap() {
        val a = RoundRect(40f, 50f, 180f, 130f, CornerRadius(24f))
        val b = RoundRect(165f, 50f, 305f, 130f, CornerRadius(24f))
        val scenes = listOf(.05f, .5f, .99999994f).flatMap { opacity ->
            listOf(listOf(a to opacity), listOf(b to 1f), listOf(a to opacity, b to 1f))
        }
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val cache = remember { GlassShadowGeometry() }
                Column(Modifier.background(Color.White).testTag("weighted-window")) {
                    PresentationMarker { stage + 1 }
                    Canvas(Modifier.size(360.dp, 210.dp)) { glassShadows(scenes[stage], 1f, .6f, cache) }
                }
            }
        }
        val captures = mutableListOf<PixelMap>()
        val failures = mutableListOf<String>()
        for (index in scenes.indices) {
            compose.runOnIdle { stage = index }
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("weighted-window"), index + 1) { view }
            captures += pixels
            if (index % 3 == 2) {
                var maximum = 0f
                for (y in 132 until 175) for (x in 10 until 340) {
                    val expected = minOf(captures[index - 2][x, y + 8].red, captures[index - 1][x, y + 8].red)
                    maximum = maxOf(maximum, abs(pixels[x, y + 8].red - expected))
                }
                android.util.Log.i("ShadowUnionWindow", "case=${index / 3} maximumFieldError=$maximum")
                if (maximum > 5f / 255f) failures += "${index / 3}:$maximum"
            }
        }
        assertTrue("Window weighted overlap error: $failures", failures.isEmpty())
    }
}
