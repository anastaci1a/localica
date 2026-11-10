package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoneControlPolicyTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun whiteRestingControlsKeepGeometryAndInputWithoutSourceLeasesInBothThemes() {
        val runtime = GlassRendererRuntime(GlassRendererMode.SingleToneBlur)
        var dark by mutableStateOf(false)
        var value by mutableFloatStateOf(.5f)
        var checked by mutableStateOf(false)
        var lensDraws = 0
        lateinit var source: FrostedBackdrop
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
                GlassPanelTheme(dark) {
                    source = rememberFrostedBackdrop(Unit, dark = dark)
                    CompositionLocalProvider(LocalFrostedBackdrop provides source,
                        LocalControlLensDraw provides { lensDraws++ }) {
                        Column(Modifier.width(300.dp).background(if (dark) Color.Black else Color.Gray)) {
                            InterfaceSlider(value, { value = it }, Modifier.testTag("policy-slider"))
                            BooleanPreference("Policy switch", checked) { checked = it }
                            InterfaceSlider(.6f, {}, Modifier.testTag("disabled-slider"), enabled = false)
                        }
                    }
                }
            } }
        }
        val slider = compose.onNodeWithTag("policy-slider")
        // Two sliders deliberately share the production thumb tag.
        fun activeThumb() = compose.onAllNodesWithTag("settings-slider-thumb", useUnmergedTree = true)[0]
        fun whiteAndResting() {
            val bounds = activeThumb().fetchSemanticsNode().boundsInRoot
            val image = activeThumb().captureToImage().toPixelMap()
            val center = image[image.width / 2, image.height / 2]
            assertTrue("Opaque white resting knob: $center", center.red > .98f && center.green > .98f && center.blue > .98f)
            assertEquals("No elastic width change", 28f, bounds.width / compose.density.density, .6f)
            assertEquals("No elastic height change", 28f, bounds.height / compose.density.density, .6f)
            compose.runOnIdle {
                assertEquals("None controls never subscribe to an undrawn source", 0, source.consumers)
                assertEquals("No optical control draws", 0, lensDraws)
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; value = .5f; checked = false }
            whiteAndResting()
            slider.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(400)
            whiteAndResting()
            slider.performTouchInput { moveTo(Offset(width * .8f, centerY)) }
            compose.mainClock.advanceTimeBy(100)
            whiteAndResting()
            slider.performTouchInput { cancel() }
            compose.waitForIdle()
            whiteAndResting()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
            compose.runOnIdle { assertEquals(.8f, value, .001f) }
            compose.onNodeWithText("Policy switch").performClick().assertIsOn()
            compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput {
                down(Offset(width * .75f, centerY))
            }
            compose.mainClock.advanceTimeBy(400)
            val switch = compose.onNodeWithTag("settings-switch", useUnmergedTree = true).captureToImage().toPixelMap()
            val knob = switch[(switch.width * 44f / 60f).toInt(), switch.height / 2]
            assertTrue("Held switch stays white in theme=$theme: $knob", knob.red > .98f && knob.green > .98f && knob.blue > .98f)
            compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { cancel() }
            compose.onNodeWithText("Policy switch").assertIsOn()
            compose.onNodeWithTag("disabled-slider").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, source.consumers); assertEquals(0, lensDraws) }
        }
    }
}
