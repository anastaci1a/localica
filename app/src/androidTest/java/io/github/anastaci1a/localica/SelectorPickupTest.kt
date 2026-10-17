package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real controls and actual parent displacement: no inferred scroll winner from direction alone. */
class SelectorPickupTest {
    @get:Rule val compose = createComposeRule()
    private var selected by mutableIntStateOf(0)
    private var changes = 0
    private val pulses = mutableListOf<ControlHaptic>()
    private lateinit var scroll: ScrollState

    private fun choices(horizontal: Boolean = true, parent: Boolean = false, rtl: Boolean = false,
        reduced: Boolean = true) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalControlHaptics provides ControlHaptics { pulses += it }) {
                scroll = rememberScrollState()
                Column(Modifier.width(300.dp).height(450.dp)
                    .then(if (parent) Modifier.controlVerticalScroll(scroll) else Modifier)) {
                    Spacer(Modifier.height(150.dp))
                    GlassChoices(listOf("Ascending", "Descending"), selected, horizontal = horizontal) {
                        selected = it; changes++
                    }
                    if (parent) Spacer(Modifier.height(900.dp))
                }
            }
        } }
    }

    @Test fun otherOptionTapAndAccessibilityEachEmitOneWeak() {
        choices()
        compose.onNodeWithTag("choice-option-1").performTouchInput { click() }
        compose.onNodeWithTag("choice-option-0").performClick()
        compose.runOnIdle { assertEquals(0, selected); assertEquals(2, changes); assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun horizontalOtherOptionPreviewsThenHeldContactKeepsDragging() = heldPickup(horizontal = true)
    @Test fun verticalOtherOptionPreviewsThenHeldContactKeepsDragging() = heldPickup(horizontal = false)
    @Test fun rtlOtherOptionPreviewsThenHeldContactKeepsDragging() = heldPickup(horizontal = true, rtl = true)

    private fun heldPickup(horizontal: Boolean, rtl: Boolean = false) {
        choices(horizontal, rtl = rtl)
        val option = compose.onNodeWithTag("choice-option-1")
        compose.mainClock.autoAdvance = false
        try {
            option.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            val lens = compose.onNodeWithTag("choice-lens").getUnclippedBoundsInRoot()
            val target = option.getUnclippedBoundsInRoot()
            assertEquals(if (horizontal) (target.left.value + target.right.value) / 2f else (target.top.value + target.bottom.value) / 2f,
                if (horizontal) (lens.left.value + lens.right.value) / 2f else (lens.top.value + lens.bottom.value) / 2f, 1f)
            compose.runOnIdle { assertEquals(0, changes); assertTrue(pulses.isEmpty()) }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { assertEquals(0, changes); assertEquals(listOf(ControlHaptic.Weak), pulses) }
            option.performTouchInput {
                moveBy(if (horizontal) Offset(width.toFloat() * if (rtl) 1f else -1f, 0f)
                    else Offset(0f, -height.toFloat()), delayMillis = 100)
                up()
            }
            compose.runOnIdle {
                assertEquals(0, selected); assertEquals(1, changes)
                assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun horizontalSelectedOriginCrossFirstStillDragsWithoutParent() = crossThenAxial(true, 0, 40f)
    @Test fun horizontalOtherOriginCrossFirstStillDragsWithoutParent() = crossThenAxial(true, 1, 40f)
    @Test fun horizontalSmallCrossExcursionStillDragsWithoutParent() = crossThenAxial(true, 1, 2f)
    @Test fun verticalEquivalentRetainsSameContactWithoutParent() = crossThenAxial(false, 1, 40f)

    private fun crossThenAxial(horizontal: Boolean, origin: Int, cross: Float) {
        choices(horizontal)
        compose.onNodeWithTag("choice-option-$origin").performTouchInput {
            down(center)
            moveBy(if (horizontal) Offset(0f, cross) else Offset(cross, 0f), delayMillis = 16)
            val sign = if (origin == 0) 1f else -1f
            moveBy(if (horizontal) Offset(sign * width * 1.2f, 0f)
                else Offset(0f, sign * height * 1.2f), delayMillis = 120)
            up()
        }
        compose.runOnIdle {
            assertEquals(1 - origin, selected); assertEquals(1, changes); assertEquals(0, scroll.value)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun selectedOriginParentScrollWinnerStaysOwnerAfterAxialTurn() = parentWins(0)
    @Test fun otherOriginParentScrollWinnerStaysOwnerAfterAxialTurn() = parentWins(1)

    private fun parentWins(origin: Int) {
        choices(parent = true)
        compose.onNodeWithTag("choice-option-$origin").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f), delayMillis = 16)
            moveBy(Offset(0f, -40f), delayMillis = 16)
            moveBy(Offset(if (origin == 0) width.toFloat() else -width.toFloat(), 0f), delayMillis = 200)
            up()
        }
        compose.runOnIdle {
            assertTrue("The real parent must have consumed scroll", scroll.value > 0)
            assertEquals(0, changes); assertEquals(0, selected); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun canceledOtherOptionPreviewRestoresSelectionWithoutPulses() {
        choices(reduced = false)
        compose.onNodeWithTag("choice-option-1").performTouchInput { down(center); cancel() }
        compose.waitForIdle()
        val lens = compose.onNodeWithTag("choice-lens").getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("choice-option-0").getUnclippedBoundsInRoot()
        assertEquals((target.left.value + target.right.value) / 2f, (lens.left.value + lens.right.value) / 2f, 1f)
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, selected); assertTrue(pulses.isEmpty()) }
    }

    @Test fun pendingSecondPointerCancelsChoiceWithoutCommitOrPulse() {
        choices()
        compose.onNodeWithTag("choice-option-1").performTouchInput {
            down(0, center); down(1, center + Offset(2f, 0f)); up(0); up(1)
        }
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, selected); assertTrue(pulses.isEmpty()) }
        compose.onNodeWithTag("choice-option-1").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, changes); assertEquals(1, selected); assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
    }

    @Test fun canceledOwnedChoiceHasNoCommitOrReleasePulse() {
        choices()
        compose.onNodeWithTag("choice-option-1").performTouchInput {
            down(center); moveBy(Offset(-width * .2f, 0f), delayMillis = 100); cancel()
        }
        compose.runOnIdle {
            assertEquals(0, changes); assertEquals(0, selected); assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
    }
}
