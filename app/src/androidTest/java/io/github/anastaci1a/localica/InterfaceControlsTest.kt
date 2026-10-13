package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InterfaceControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun clearWideThumbRevealsTrackAndKeepsAdjustableSemantics() {
        var value by mutableFloatStateOf(.5f)
        compose.setContent { LocalicaTheme { Box(Modifier.background(Color.White).padding(32.dp)) {
            InterfaceSlider(value, { value = it }, Modifier.width(300.dp).testTag("slider"))
        } } }
        val thumb = compose.onNodeWithTag("settings-slider-thumb", useUnmergedTree = true)
        val bounds = thumb.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > bounds.height * 1.3f)
        val pixels = thumb.captureToImage().toPixelMap()
        val active = pixels[(pixels.width * .4f).toInt(), pixels.height / 2]
        assertTrue("Active track remains visible through the clear center: $active", active.red > active.green + .12f)
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        compose.runOnIdle { assertEquals(.8f, value, .001f) }
    }

    @Test fun switchTapAndDragChangeTheSameAccessibleState() {
        var checked by mutableStateOf(false)
        compose.setContent { LocalicaTheme { Column(Modifier.width(300.dp)) {
            BooleanPreference("Example switch", checked) { checked = it }
        } } }
        compose.onNodeWithText("Example switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width - 8f, center.y), Offset(4f, center.y), durationMillis = 350)
        }
        compose.onNodeWithText("Example switch").assertIsOff()
    }

    @Test fun cancelledSwitchAndChoiceDragsRestoreTheirCommittedValues() {
        var changes = 0
        compose.setContent { LocalicaTheme { Column(Modifier.width(300.dp).padding(top = 40.dp)) {
            BooleanPreference("Keep off", false) { changes++ }
            GlassChoices(listOf("First", "Second"), 0, horizontal = true) { changes++ }
        } } }
        val lens = compose.onNodeWithTag("choice-lens", useUnmergedTree = true)
        val resting = lens.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput {
            down(Offset(8f, center.y)); moveBy(Offset(width.toFloat(), 0f)); cancel()
        }
        compose.onNodeWithText("Keep off").assertIsOff()
        compose.onNodeWithText("First").performTouchInput {
            down(center); moveBy(Offset(width.toFloat(), 0f)); cancel()
        }
        compose.onNodeWithText("First").assertIsSelected()
        assertEquals(resting, lens.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals("Cancellation must not commit a value", 0, changes) }
    }

    @Test fun rtlControlsKeepTrackTravelAndSelectionInTheSameDirection() {
        var value by mutableFloatStateOf(.5f)
        var checked by mutableStateOf(false)
        var selection by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                Column(Modifier.width(300.dp).background(Color.White).padding(top = 40.dp)) {
                    InterfaceSlider(value, { value = it }, Modifier.testTag("rtl-slider"))
                    BooleanPreference("RTL switch", checked) { checked = it }
                    GlassChoices(listOf("First", "Second"), selection, horizontal = true) { selection = it }
                }
            }
        } }
        val pixels = compose.onNodeWithTag("settings-slider-thumb", useUnmergedTree = true).captureToImage().toPixelMap()
        val active = pixels[(pixels.width * .6f).toInt(), pixels.height / 2]
        assertTrue("RTL active track is visible on the right: $active", active.red > active.green + .12f)
        compose.onNodeWithTag("rtl-slider").performTouchInput { swipe(center, Offset(4f, center.y), 350) }
        compose.runOnIdle { assertTrue(value > .9f) }
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width - 8f, center.y), Offset(4f, center.y), 350)
        }
        compose.onNodeWithText("RTL switch").assertIsOn()
        compose.onNodeWithText("First").performTouchInput { swipe(center, Offset(-width / 2f, center.y), 350) }
        compose.onNodeWithText("Second").assertIsSelected()
    }

    @Test fun sliderKeepsDiscreteAndDisabledSemantics() {
        var value by mutableFloatStateOf(2f)
        var enabled by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            InterfaceSlider(value, { value = it }, Modifier.width(300.dp).testTag("steps"), 1f..5f, 3, enabled)
        } }
        compose.onNodeWithTag("steps").performSemanticsAction(SemanticsActions.SetProgress) { it(3.7f) }
        compose.runOnIdle { assertEquals(4f, value); enabled = false }
        compose.onNodeWithTag("steps").assertIsNotEnabled().performTouchInput { swipe(center, Offset(0f, center.y), 350) }
        compose.runOnIdle { assertEquals(4f, value) }
    }

    @Test fun leavingAButtonKeepsPressureUntilReleaseWithoutActivatingIt() {
        lateinit var contact: GlassContact
        var clicks = 0
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().padding(40.dp)) {
            contact = rememberGlassContact()
            Box(Modifier.size(80.dp).testTag("press").glassPress(contact)
                .clickable(interactionSource = null, indication = null) { clicks++ })
        } } }
        compose.onNodeWithTag("press").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(contact.pressed) }
        compose.onNodeWithTag("press").performTouchInput { moveBy(Offset(width * 2f, 0f)) }
        compose.runOnIdle { assertTrue(contact.pressed) }
        compose.onNodeWithTag("press").performTouchInput { up() }
        compose.runOnIdle { assertFalse(contact.pressed); assertEquals(0, clicks); assertEquals(1f, contact.body.value.scaleX(), .001f) }
    }
}
