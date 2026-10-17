package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ButtonGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedButtonsKeepTheirContactWithoutScrollingOrActivatingAfterDrag() {
        lateinit var scroll: ScrollState
        var clicks = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(450.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                GlassActionButton(R.drawable.ic_plus, "Glass action") { clicks++ }
                SettingsLink("Setting action") { clicks++ }
                ActionTextButton({ clicks++ }) { Text("Text action") }
                ActionIconButton({ clicks++ }, Modifier.testTag("icon-action")) { Text("Icon") }
                Spacer(Modifier.height(900.dp))
            }
        } }
        val actions = listOf(compose.onNodeWithText("Glass action"), compose.onNodeWithText("Setting action"),
            compose.onNodeWithText("Text action"), compose.onNodeWithTag("icon-action"))
        actions.forEachIndexed { index, node ->
            node.performTouchInput { down(center); moveBy(Offset(1f, 1f)); up() }
            compose.runOnIdle { assertEquals("Small finger jitter remains a tap", index + 1, clicks) }
            node.performTouchInput {
                down(center)
                // Gradual unheld motion is distinct from the held jump below.
                repeat(10) { moveBy(Offset(0f, -16f), delayMillis = 100) }
                up()
            }
            compose.runOnIdle { assertEquals(0, scroll.value); assertEquals(index + 1, clicks) }
            node.performTouchInput {
                down(center)
                advanceEventTime(200)
                moveBy(Offset(0f, -160f), delayMillis = 100)
                up()
            }
            compose.runOnIdle { assertEquals(0, scroll.value); assertEquals(index + 1, clicks) }
        }
    }

    @Test fun fastButtonSwipeIsSilentAndNextTapActivatesExactlyOnce() {
        lateinit var scroll: ScrollState
        var clicks = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                scroll = rememberScrollState()
                Column(Modifier.height(450.dp).controlVerticalScroll(scroll)) {
                    Spacer(Modifier.height(100.dp))
                    ActionTextButton({ clicks++ }, Modifier.testTag("scroll-origin")) { Text("Action") }
                    Spacer(Modifier.height(900.dp))
                }
            }
        } }
        compose.onNodeWithTag("scroll-origin").performTouchInput {
            swipe(center, center + Offset(0f, -160f), 120)
        }
        compose.runOnIdle {
            assertTrue("Fast intent reaches the parent", scroll.value > 0)
            assertEquals("Scrolling cannot activate the button", 0, clicks)
            assertTrue("Scroll cancellation is silent", pulses.isEmpty())
            scroll.dispatchRawDelta(-scroll.value.toFloat())
        }
        compose.onNodeWithTag("scroll-origin").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
    }

    @Test fun sameAxisChoiceFastSwipeScrollsButHeldAdjustmentSelects() {
        lateinit var scroll: ScrollState
        var selected by mutableIntStateOf(1)
        var changes = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                GlassChoices(listOf("First", "Second", "Third"), selected) { selected = it; changes++ }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.onNodeWithTag("choice-option-1", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, -160f), 120)
        }
        compose.runOnIdle {
            assertTrue(scroll.value > 0)
            assertEquals(0, changes)
            scroll.dispatchRawDelta(-scroll.value.toFloat())
        }
        compose.onNodeWithTag("choice-option-1", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(200)
            moveBy(Offset(0f, -160f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertEquals(0, selected)
            assertEquals(1, changes)
        }
    }

    @Test fun sliderAndSwitchYieldVerticalSweepsWithoutChangingValues() {
        lateinit var scroll: ScrollState
        var changes = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                InterfaceSlider(.5f, { changes++ }, Modifier.testTag("scroll-slider"))
                BooleanPreference("Scroll switch", false) { changes++ }
                Spacer(Modifier.height(900.dp))
            }
        } }
        for (tag in listOf("scroll-slider", "settings-switch")) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
                swipe(center, center + Offset(0f, -160f), 120)
            }
            compose.runOnIdle {
                assertTrue("$tag lets the parent scroll", scroll.value > 0)
                assertEquals("$tag does not commit during a scroll", 0, changes)
                scroll.dispatchRawDelta(-scroll.value.toFloat())
            }
        }
    }

    @Test fun nestedHorizontalParentRetainsVerticalAncestorScrollIntent() {
        lateinit var vertical: ScrollState
        lateinit var horizontal: ScrollState
        var clicks = 0
        compose.setContent { LocalicaTheme {
            vertical = rememberScrollState(); horizontal = rememberScrollState()
            Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(vertical)) {
                Spacer(Modifier.height(100.dp))
                Row(Modifier.controlHorizontalScroll(horizontal)) {
                    ActionTextButton({ clicks++ }, Modifier.width(200.dp).testTag("nested-action")) { Text("Nested") }
                    Spacer(Modifier.width(800.dp))
                }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.onNodeWithTag("nested-action").performTouchInput {
            swipe(center, center + Offset(0f, -160f), 120)
        }
        compose.runOnIdle {
            assertTrue(vertical.value > 0); assertEquals(0, horizontal.value); assertEquals(0, clicks)
            vertical.dispatchRawDelta(-vertical.value.toFloat())
        }
        compose.onNodeWithTag("nested-action").performTouchInput {
            swipe(center, center + Offset(-160f, 0f), 120)
        }
        compose.runOnIdle {
            assertEquals(0, vertical.value); assertTrue(horizontal.value > 0); assertEquals(0, clicks)
        }
    }

    @Test fun fittingInnerScrollKeepsVerticalAncestorIntent() = innerScrollKeepsAncestor(enabled = true)

    @Test fun disabledInnerScrollKeepsVerticalAncestorIntent() = innerScrollKeepsAncestor(enabled = false)

    private fun innerScrollKeepsAncestor(enabled: Boolean) {
        lateinit var vertical: ScrollState
        lateinit var horizontal: ScrollState
        var clicks = 0
        compose.setContent { LocalicaTheme {
            vertical = rememberScrollState(); horizontal = rememberScrollState()
            Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(vertical)) {
                Spacer(Modifier.height(100.dp))
                Row(Modifier.controlHorizontalScroll(horizontal, enabled = enabled)) {
                    ActionTextButton({ clicks++ }, Modifier.width(200.dp).testTag("inner-action")) { Text("Nested") }
                    if (!enabled) Spacer(Modifier.width(800.dp))
                }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.onNodeWithTag("inner-action").performTouchInput {
            swipe(center, center + Offset(0f, -160f), 120)
        }
        compose.runOnIdle {
            assertTrue(vertical.value > 0); assertEquals(0, horizontal.value); assertEquals(0, clicks)
        }
    }

    @Test fun cancelledPendingSliderContactNeverCommits() {
        var changes = 0
        compose.setContent { LocalicaTheme {
            InterfaceSlider(.5f, { changes++ }, Modifier.width(300.dp).testTag("cancel-slider"))
        } }
        compose.onNodeWithTag("cancel-slider").performTouchInput {
            down(center); moveBy(Offset(1f, 1f)); cancel()
        }
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun horizontalSelectedChoiceContinuesAfterPerpendicularMotionWithoutParent() =
        selectedChoiceCrossThenAxial(horizontal = true)

    @Test fun verticalSelectedChoiceContinuesAfterPerpendicularMotionWithoutParent() =
        selectedChoiceCrossThenAxial(horizontal = false)

    private fun selectedChoiceCrossThenAxial(horizontal: Boolean) {
        var selected by mutableIntStateOf(0)
        var changes = 0
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(300.dp)) {
                GlassChoices(listOf("First", "Second", "Third"), selected, horizontal = horizontal) {
                    selected = it; changes++
                }
            }
        } }
        compose.onNodeWithTag("choice-option-0", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(if (horizontal) Offset(0f, 40f) else Offset(40f, 0f), delayMillis = 16)
            moveBy(if (horizontal) Offset(width * 2f, 0f) else Offset(0f, height * 2f), delayMillis = 120)
            up()
        }
        compose.runOnIdle { assertEquals(2, selected); assertEquals(1, changes) }
    }

    @Test fun parentScrollWinnerDoesNotActivateAfterAxialReversalAndStaysSilent() {
        lateinit var scroll: ScrollState
        var changes = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                scroll = rememberScrollState()
                Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(scroll)) {
                    Spacer(Modifier.height(150.dp))
                    GlassChoices(listOf("First", "Second"), 0, horizontal = true) { changes++ }
                    Spacer(Modifier.height(900.dp))
                }
            }
        } }
        compose.onNodeWithTag("choice-option-0", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f), delayMillis = 16)
            moveBy(Offset(0f, -40f), delayMillis = 16)
            moveBy(Offset(width.toFloat(), 0f), delayMillis = 160)
            up()
        }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertEquals(0, changes); assertTrue(pulses.isEmpty()) }
    }

    @Test fun selectionSurfacesStillAllowScrollingAndSelection() {
        lateinit var scroll: ScrollState
        var selected by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(400.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                LiquidGlassSurface(Modifier.size(180.dp, 100.dp).testTag("selection")
                    .selectable(selected) { selected = !selected }) { Text("Selectable content") }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.onNodeWithTag("selection").performTouchInput { click() }
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithTag("selection").performTouchInput { swipe(center, center + Offset(0f, -120f), 400) }
        compose.runOnIdle { assertTrue("Selection does not capture scrolling", scroll.value > 0); assertTrue(selected) }
    }
}
