package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassPresenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenFootprintIsStableAndRetainedExitReleasesInputImmediately() {
        var visible by mutableStateOf(false)
        var frontClicks = 0
        var behindClicks = 0
        compose.setContent { LocalicaTheme {
            Box(Modifier.padding(40.dp).testTag("slot")) {
                Box(Modifier.matchParentSize().background(Color.White).clickable { behindClicks++ })
                GlassPresence(visible) {
                    GlassActionButton(R.drawable.ic_seek_back, "Revert", onClick = { frontClicks++ })
                }
            }
        } }
        val hiddenBounds = compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Revert").performClick()
        assertEquals(hiddenBounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Revert").assertDoesNotExist()
        val exiting = compose.onNodeWithTag("slot").captureToImage().toPixelMap()
        compose.onNodeWithTag("slot").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, frontClicks); assertEquals(1, behindClicks) }
        compose.mainClock.advanceTimeBy(1600)
        val hidden = compose.onNodeWithTag("slot").captureToImage().toPixelMap()
        assertEquals(hiddenBounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        var differences = 0
        for (y in 0 until hidden.height) for (x in 0 until hidden.width) {
            if (hidden[x, y] != exiting[x, y]) differences++
        }
        assertTrue("Closing glass must remain drawn after its input disappears", differences > 20)
    }

    @Test fun reversingPresenceKeepsItsFootprintAndReducedMotionRemovesExitImmediately() {
        var visible by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            Box(Modifier.padding(40.dp).testTag("slot")) {
                GlassPresence(visible) { GlassActionButton(R.drawable.ic_seek_back, "Revert", onClick = {}) }
            }
        } } }
        compose.onNodeWithText("Revert").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Revert").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { reduced = true; visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Revert").assertIsDisplayed()
    }
}
