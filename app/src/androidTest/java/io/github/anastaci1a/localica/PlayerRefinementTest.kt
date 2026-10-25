package io.github.anastaci1a.localica

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerRefinementTest {
    @get:Rule val compose = createComposeRule()

    private fun assertDrawnArrowPointsUp(expected: Boolean) {
        // Inspect the off-axis arrow wings, not accessibility that must disappear on exit.
        // Opposite samples cancel the circular glass surface and the centered shaft.
        val image = compose.onNodeWithTag("return-drawing").captureToImage().toPixelMap()
        var upper = 0f
        var lower = 0f
        for (x in 0 until image.width) {
            val fraction = (x + .5f) / image.width
            if (fraction !in .31f.. .46f && fraction !in .54f.. .69f) continue
            for (y in (image.height * .25f).toInt() until image.height / 2) {
                upper += 1f - image[x, y].red
                lower += 1f - image[x, image.height - 1 - y].red
            }
        }
        assertTrue("The retained arrow must point ${if (expected) "up" else "down"}: upper=$upper lower=$lower",
            if (expected) upper > lower + 1f else lower > upper + 1f)
    }

    @Test fun returningToCurrentPositionKeepsDirectionThroughoutExitWithoutRetainingInput() {
        var visible by mutableStateOf(true)
        var up by mutableStateOf(true)
        var clicks = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides Color.Black,
                LocalControlHaptics provides ControlHaptics { pulses += it },
                LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                Box(Modifier.size(CompactControlSize).background(Color.White).testTag("return-drawing")) {
                    AnimatedVisibility(visible, exit = fadeOut(tween(300))) {
                        ReturnToPositionButton(up, "Current song", visible) { clicks++ }
                    }
                }
            }
        } }
        val button = compose.onNodeWithContentDescription("Current song")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll up"))
        assertDrawnArrowPointsUp(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible = false; up = false }
            compose.mainClock.advanceTimeBy(150)
            button.assertDoesNotExist()
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
            assertDrawnArrowPointsUp(true)
            compose.onNodeWithTag("return-drawing").performTouchInput { click() }
            compose.runOnIdle { assertEquals(0, clicks); assertTrue(pulses.isEmpty()) }
            compose.mainClock.advanceTimeBy(350)
            button.assertDoesNotExist()
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeBy(500)
            button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll down"))
            assertDrawnArrowPointsUp(false)
            button.performClick()
            compose.runOnIdle { assertEquals(1, clicks); assertEquals(listOf(ControlHaptic.Weak), pulses) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun floatingReturnReleasesAccessibleAndTouchActionsThroughInterruptedAndFreshVisibility() {
        var visible by mutableStateOf(true)
        var clicks = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }, LocalMotionSpeed provides 1f) {
                Box(Modifier.fillMaxSize().testTag("return-canvas")) {
                    FloatingReturnToPositionButton(true, "Return to top", visible, reducedMotion = false,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).testTag("floating-return")) { clicks++ }
                }
            }
        } }
        val button = compose.onNodeWithContentDescription("Return to top")
        button.assertIsDisplayed().assertHasClickAction()
        val canvas = compose.onNodeWithTag("return-canvas")
        val center = button.fetchSemanticsNode().boundsInRoot.center - canvas.fetchSemanticsNode().boundsInRoot.topLeft
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("floating-return").assertExists()
            button.assertDoesNotExist()
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
            canvas.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(0, clicks); assertTrue(pulses.isEmpty()); visible = true }
            compose.mainClock.advanceTimeBy(80)
            button.assertIsDisplayed().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(1, clicks); visible = false }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("floating-return").assertDoesNotExist()
            button.assertDoesNotExist()
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeBy(500)
            button.assertIsDisplayed().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(2, clicks); assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak), pulses) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
