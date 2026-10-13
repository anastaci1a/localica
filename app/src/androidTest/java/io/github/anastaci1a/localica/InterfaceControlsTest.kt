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
}
