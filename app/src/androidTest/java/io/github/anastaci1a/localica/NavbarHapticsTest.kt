package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavbarHapticsTest {
    @get:Rule val compose = createComposeRule()
    private val pulses = mutableListOf<ControlHaptic>()
    private val sink = ControlHaptics { pulses += it }
    private val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
    private var page by mutableFloatStateOf(0f)
    private var pageDragging by mutableStateOf(false)
    private var reduced by mutableStateOf(false)
    private var selections = 0

    private fun fixture(overflow: Boolean = false, rtl: Boolean = false, deferSelection: Boolean = false) {
        val choices = if (overflow) MainTab.entries.take(8) else tabs
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides sink, LocalReducedMotion provides reduced,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(if (overflow) 260.dp else 300.dp, 64.dp).testTag("haptic-tabs")) {
                    SlidingTabs(choices, choices[page.toInt().coerceIn(choices.indices)], { page },
                        pageDragging = pageDragging) { selections++; if (!deferSelection) page = choices.indexOf(it).toFloat() }
                }
            }
        } }
    }

    @Test fun tapReselectAndSemanticActivationEachEmitExactlyOneWeakPulse() {
        fixture()
        compose.onNodeWithTag("tab-option-1").performTouchInput { click() }
        compose.onNodeWithTag("tab-option-1").performTouchInput { click() }
        compose.onNodeWithTag("tab-option-0").performClick()
        compose.runOnIdle { assertEquals(List(3) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun pageSwipeTrackingProgrammaticChangeSettlingAndRecompositionStaySilent() {
        fixture()
        compose.runOnIdle { pageDragging = true; page = .7f }
        compose.waitForIdle()
        compose.runOnIdle { page = 1.6f }
        compose.waitForIdle()
        compose.runOnIdle { pageDragging = false; page = 2f }
        compose.waitForIdle()
        compose.runOnIdle { page = 0f; reduced = true }
        compose.waitForIdle()
        compose.runOnIdle { reduced = false }
        compose.runOnIdle { assertEquals(emptyList<ControlHaptic>(), pulses) }
    }

    @Test fun dragHasOneEngagementDistinctOptionTicksAndOneGenuineRelease() {
        fixture()
        val target = compose.onNodeWithTag("haptic-tabs")
        target.performTouchInput {
            down(Offset(width / 6f, centerY))
            moveBy(Offset(width * .12f, 0f))
        }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        // Recomposition during the same pointer sequence must not restart engagement.
        compose.runOnIdle { reduced = true }
        target.performTouchInput { moveBy(Offset(width * .18f, 0f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
        target.performTouchInput { moveBy(Offset(width * .01f, 0f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
        target.performTouchInput { moveBy(Offset(-width * .26f, 0f)); up() }
        compose.runOnIdle { assertEquals(List(3) { ControlHaptic.Weak } + ControlHaptic.Strong, pulses) }
    }

    @Test fun canceledDragNeverEmitsReleaseAndNextTapIsIndependent() {
        fixture()
        val target = compose.onNodeWithTag("haptic-tabs")
        target.performTouchInput {
            down(Offset(width / 6f, centerY)); moveBy(Offset(width * .12f, 0f)); cancel()
        }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        compose.onNodeWithTag("tab-option-0").performTouchInput { click() }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun pendingSecondPointerCancelsNavbarWithoutCommitOrPulse() {
        fixture()
        compose.onNodeWithTag("tab-option-1").performTouchInput {
            down(0, center); down(1, center + Offset(2f, 0f)); up(0); up(1)
        }
        compose.runOnIdle { assertEquals(0, selections); assertEquals(0f, page); assertTrue(pulses.isEmpty()) }
        compose.onNodeWithTag("tab-option-1").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, selections); assertEquals(1f, page); assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
    }

    @Test fun pointerHandoffRetainsOneOwnedGestureAndOneRelease() {
        fixture()
        compose.onNodeWithTag("haptic-tabs").performTouchInput {
            down(0, Offset(width / 6f, centerY))
            moveBy(0, Offset(width * .12f, 0f))
            down(1, Offset(width / 3f, centerY))
            up(0)
            moveBy(1, Offset(width * .01f, 0f))
            up(1)
        }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses) }
    }

    @Test fun overflowingNavbarScrollNeverEngagesSelectorFeedback() {
        fixture(overflow = true)
        compose.onNodeWithTag("tab-scroll").performTouchInput {
            swipe(Offset(width * .8f, centerY), Offset(width * .15f, centerY), 400)
        }
        compose.runOnIdle { assertEquals(emptyList<ControlHaptic>(), pulses) }
    }

    @Test fun fittingOtherOptionPreviewRemainsSilentUntilHeldPickup() {
        reduced = true
        fixture()
        val option = compose.onNodeWithTag("tab-option-2")
        compose.mainClock.autoAdvance = false
        try {
            option.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle { assertEquals(0f, page); assertTrue(pulses.isEmpty()) }
            val lens = compose.onNodeWithTag("tab-selector").getUnclippedBoundsInRoot()
            val target = option.getUnclippedBoundsInRoot()
            assertEquals((target.left.value + target.right.value) / 2f,
                (lens.left.value + lens.right.value) / 2f, 1f)
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { assertEquals(2f, page); assertEquals(listOf(ControlHaptic.Weak), pulses) }
            option.performTouchInput { moveBy(Offset(-width.toFloat(), 0f)); up() }
            compose.runOnIdle {
                assertEquals(1f, page)
                assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun canceledPreviewReturnsToAnUnfinishedTapDestination() {
        reduced = true
        fixture(deferSelection = true)
        compose.onNodeWithTag("tab-option-1").performTouchInput { click() }
        compose.onNodeWithTag("tab-option-2").performTouchInput { down(center); cancel() }
        compose.waitForIdle()
        val lens = compose.onNodeWithTag("tab-selector").getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("tab-option-1").getUnclippedBoundsInRoot()
        assertEquals((target.left.value + target.right.value) / 2f,
            (lens.left.value + lens.right.value) / 2f, 1f)
        compose.runOnIdle { assertEquals(1, selections); assertEquals(listOf(ControlHaptic.Weak), pulses) }
    }

    @Test fun overflowVisibleLensOriginDragsWithoutScrollingStrip() = overflowLensDrag(false)
    @Test fun rtlOverflowVisibleLensOriginDragsWithoutScrollingStrip() = overflowLensDrag(true)

    private fun overflowLensDrag(rtl: Boolean) {
        reduced = true
        fixture(overflow = true, rtl = rtl)
        fun scrollValue() = compose.onNodeWithTag("tab-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val before = scrollValue()
        compose.onNodeWithTag("tab-option-0").performTouchInput {
            down(center)
            moveBy(Offset(width * 1.2f * if (rtl) -1f else 1f, 0f), delayMillis = 100)
        }
        compose.runOnIdle { assertEquals(1f, page); assertEquals(listOf(ControlHaptic.Weak), pulses) }
        assertEquals("The owned contact cannot scroll the strip", before, scrollValue(), 0f)
        compose.onNodeWithTag("tab-option-0").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1f, page)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
        // Existing selected-page centering may move the strip after genuine release.
        compose.onNodeWithTag("tab-option-1").assertIsDisplayed()
    }

    @Test fun overflowOtherOriginScrollsSilentlyThenTapSelects() {
        reduced = true
        fixture(overflow = true)
        fun scrollValue() = compose.onNodeWithTag("tab-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val before = scrollValue()
        compose.onNodeWithTag("tab-option-2").performTouchInput {
            swipe(center, center + Offset(-120f, 0f), 120)
        }
        assertTrue(scrollValue() > before)
        compose.runOnIdle { assertEquals(0f, page); assertTrue(pulses.isEmpty()) }
        compose.onNodeWithTag("tab-option-2").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2f, page); assertEquals(listOf(ControlHaptic.Weak), pulses) }
    }

    @Test fun verticalMotionDoesNotClaimNavbarHaptics() {
        fixture()
        compose.onNodeWithTag("haptic-tabs").performTouchInput {
            down(Offset(width / 6f, centerY)); moveBy(Offset(0f, height * 2f)); up()
        }
        compose.runOnIdle { assertEquals(emptyList<ControlHaptic>(), pulses) }
    }
}
