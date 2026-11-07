package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

class ShadowFalloffTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun liveAndRetainedFalloffIsMonotonicWithoutRingEdges() = inspect(1f)
    @Test fun highDensityFalloffIsMonotonicWithoutRingEdges() = inspect(2f)

    private fun inspect(scale: Float) {
        var cached by mutableStateOf(false)
        lateinit var retained: RetainedGlassShadow
        val d = compose.density.density * scale
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(d)) {
            val scope = rememberCoroutineScope()
            retained = remember { RetainedGlassShadow(scope) }
            val geometry = remember { GlassShadowGeometry() }
            androidx.compose.foundation.layout.Box(Modifier.size(200.dp).background(Color.White)
                .testTag("shadow-falloff").drawBehind {
                    val bodies = listOf(RoundRect(30.dp.toPx(),70.dp.toPx(),130.dp.toPx(),130.dp.toPx(),
                        CornerRadius(30.dp.toPx())) to 1f)
                    if (cached) with(retained) { draw(bodies, 1f, 1f) }
                    else glassShadows(bodies, 1f, 1f, geometry)
                })
            }
        }
        val reach = 32*d
        for (mode in listOf(false, true)) {
            compose.runOnIdle { cached = mode }
            if (mode) compose.waitUntil(5000) { retained.rasterizations > 0 }
            val pixels = compose.onNodeWithTag("shadow-falloff").captureToImage().toPixelMap()
            val profile = (1..ceil(reach).toInt()+2).map { offset ->
                val x = (130*d).toInt()+offset
                ((96*d).toInt() until (104*d).toInt()).map { y -> pixels[x,y].red }.average().toFloat()
            }
            val steps = profile.zipWithNext { a,b -> b-a }
            val maximumStep = steps.max()
            val maximumSlopeJump = steps.zipWithNext { a,b -> abs(a-b) }.max()
            assertTrue("The profile must include a substantial shadow", profile.first() < .8f)
            assertTrue("Falloff must be monotonic: cached=$mode $profile", steps.min() >= -1f/255f)
            assertTrue("No discrete ring edge: cached=$mode step=$maximumStep profile=$profile",
                maximumStep <= .75f/reach + 1f/255f)
            assertTrue("No alternating plateaus and ring steps: cached=$mode slopeJump=$maximumSlopeJump profile=$profile",
                maximumSlopeJump <= 2.01f/255f)
            assertTrue("The outer tail reaches the background", profile.takeLast(3).min() > .996f)
        }
    }
}
