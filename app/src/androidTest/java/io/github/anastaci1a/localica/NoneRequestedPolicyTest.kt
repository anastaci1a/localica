package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoneRequestedPolicyTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun requestedModeHonorsAbsentRuntimeOverridesAndNormalFallback() {
        var runtime by mutableStateOf<GlassRendererRuntime?>(null)
        var appearance by mutableStateOf(GlassRendererMode.Normal)
        var actual = false
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime,
                LocalGlassAppearance provides GlassAppearance(renderer = appearance)) {
                val selected = requestedGlassNone()
                SideEffect { actual = selected }
            }
        }
        fun check(holder: GlassRendererRuntime?, local: GlassRendererMode, expected: Boolean) {
            compose.runOnIdle { runtime = holder; appearance = local }
            compose.runOnIdle { assertEquals("runtime=${holder?.requested} appearance=$local", expected, actual) }
        }
        check(null, GlassRendererMode.Normal, false)
        check(null, GlassRendererMode.None, true)
        check(GlassRendererRuntime(GlassRendererMode.Normal, 26), GlassRendererMode.None, false)
        check(GlassRendererRuntime(GlassRendererMode.None, 26), GlassRendererMode.Normal, true)
        val holder = GlassRendererRuntime(GlassRendererMode.Normal)
        check(holder, GlassRendererMode.None, false)
        compose.runOnIdle { holder.request(GlassRendererMode.None) }
        compose.runOnIdle { assertTrue(actual) }
        compose.runOnIdle { holder.request(GlassRendererMode.Normal) }
        compose.runOnIdle { assertFalse(actual) }
    }

    @Test fun heldModeReversalKeepsValueOwnerAndDiscardsPrivateElasticMomentum() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var value by mutableFloatStateOf(.5f)
        lateinit var source: FrostedBackdrop
        val expansion = 1.75f
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(controlExpansion = expansion)) {
                    // Deliberately unrecorded: None must be independent of producer readiness.
                    source = rememberFrostedBackdrop(Unit)
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        InterfaceSlider(value, { value = it }, Modifier.width(300.dp).background(Color.Black).testTag("reversal-slider"))
                    }
                }
            } }
        }
        val slider = compose.onNodeWithTag("reversal-slider")
        val thumb = compose.onNodeWithTag("settings-slider-thumb", useUnmergedTree = true)
        compose.mainClock.autoAdvance = false
        try {
            slider.performTouchInput { down(center); moveTo(Offset(width * .6f, centerY)) }
            compose.mainClock.advanceTimeBy(400)
            for (fraction in listOf(.9f, .1f, .85f)) {
                compose.runOnIdle { runtime.request(GlassRendererMode.None) }
                compose.mainClock.advanceTimeByFrame()
                slider.performTouchInput { moveTo(Offset(width * fraction, centerY), delayMillis = 32) }
                compose.mainClock.advanceTimeBy(1000)
                val resting = thumb.fetchSemanticsNode().boundsInRoot
                assertEquals(28f, resting.width / compose.density.density, .6f)
                assertEquals(28f, resting.height / compose.density.density, .6f)
                compose.runOnIdle { assertEquals(0, source.consumers) }
                compose.runOnIdle { runtime.request(GlassRendererMode.Normal) }
                repeat(4) {
                    compose.mainClock.advanceTimeByFrame()
                    val bounds = thumb.fetchSemanticsNode().boundsInRoot
                    val ratio = expansion / (1f + (expansion - 1f) * 2f / 9f)
                    assertEquals("No stale velocity strain after None drag", ratio, bounds.width / bounds.height, .035f)
                }
            }
            slider.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(800)
            compose.runOnIdle { assertTrue("Original gesture still commits its final value", value > .7f) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
