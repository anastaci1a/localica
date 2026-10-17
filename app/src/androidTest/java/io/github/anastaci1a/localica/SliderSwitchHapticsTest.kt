package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SliderSwitchHapticsTest {
    @get:Rule val compose = createComposeRule()
    private val pulses = mutableListOf<ControlHaptic>()
    private val sink = ControlHaptics { pulses += it }
    private val sliderChanges = mutableListOf<Float>()
    private val switchChanges = mutableListOf<Boolean>()
    private var value by mutableFloatStateOf(.5f)
    private var checked by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var reduced by mutableStateOf(false)
    private val sliderFocus = FocusRequester()
    private lateinit var scroll: ScrollState
    private enum class Parent { None, Vertical, Horizontal }

    private fun fixture(preference: Boolean = true, parent: Parent = Parent.None,
        range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 3) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides sink, LocalReducedMotion provides reduced) {
                scroll = rememberScrollState()
                val changeSwitch: (Boolean) -> Unit = { checked = it; switchChanges += it }
                val controls: @Composable () -> Unit = {
                    Column(Modifier.width(300.dp)) {
                        InterfaceSlider(value, { value = it; sliderChanges += it },
                            Modifier.testTag("haptic-slider").focusRequester(sliderFocus), range, steps, enabled)
                        if (preference) BooleanPreference("Haptic switch", checked, changeSwitch)
                        else InterfaceSwitch(checked, if (enabled) changeSwitch else null)
                    }
                }
                when (parent) {
                    Parent.None -> controls()
                    Parent.Vertical -> Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(scroll)) {
                        Spacer(Modifier.height(100.dp))
                        controls()
                        Spacer(Modifier.height(900.dp))
                    }
                    Parent.Horizontal -> Row(Modifier.width(300.dp).controlHorizontalScroll(scroll)) {
                        controls()
                        Spacer(Modifier.width(900.dp))
                    }
                }
            }
        } }
    }

    private fun slider() = compose.onNodeWithTag("haptic-slider")
    private fun switch() = compose.onNodeWithTag("settings-switch", useUnmergedTree = true)

    /** Match the real 14 dp inset in the fixture's 300 dp-wide slider. */
    private fun TouchInjectionScope.track(fraction: Float) =
        Offset(width * (14f + 272f * fraction) / 300f, centerY)

    @Test fun sliderTapReselectionSemanticsAndKeyboardEachEmitOneWeakPulse() {
        fixture()
        slider().performTouchInput { down(track(.75f)) }
        compose.runOnIdle { assertTrue(pulses.isEmpty()); assertTrue(sliderChanges.isEmpty()) }
        slider().performTouchInput { up() }
        slider().performTouchInput { click(track(.75f)) }
        slider().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.24f)) }
        compose.runOnIdle { sliderFocus.requestFocus() }
        slider().performKeyInput { keyDown(Key.DirectionRight) }
        compose.runOnIdle {
            assertEquals(listOf(.75f, .75f, .25f, .5f), sliderChanges)
            assertEquals(List(4) { ControlHaptic.Weak }, pulses)
        }
        slider().performKeyInput { keyUp(Key.DirectionRight); pressKey(Key.A) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(4, sliderChanges.size); assertEquals(List(4) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun sliderOwnedDragDeduplicatesStepsAndRetainsItsBaselineAcrossExternalUpdates() {
        fixture()
        slider().performTouchInput { down(center); moveTo(track(.6f)) }
        compose.runOnIdle {
            assertEquals(listOf(.5f), sliderChanges)
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            value = 1f; reduced = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        slider().performTouchInput { moveTo(track(.75f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
        slider().performTouchInput { moveTo(track(.76f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
        slider().performTouchInput { moveTo(track(.5f)); up() }
        compose.runOnIdle {
            assertEquals(listOf(.5f, .75f, .75f, .5f, .5f), sliderChanges)
            assertEquals(List(3) { ControlHaptic.Weak } + ControlHaptic.Strong, pulses)
        }
    }

    @Test fun percentageSliderInitialJumpEngagesOnceAndUsesItsActualFivePercentStops() {
        fixture(steps = 0)
        slider().performTouchInput { down(center); moveTo(track(.75f)) }
        compose.runOnIdle { assertEquals(listOf(.75f), sliderChanges); assertEquals(listOf(ControlHaptic.Weak), pulses) }
        slider().performTouchInput { moveTo(track(.76f)); moveTo(track(.8f)); up() }
        compose.runOnIdle {
            assertEquals(listOf(.75f, .75f, .8f, .8f), sliderChanges)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun continuousSliderHasEngagementAndReleaseWithoutInventingStepTicks() {
        value = 50f
        fixture(range = 0f..100f, steps = 0)
        slider().performTouchInput {
            down(center); moveTo(track(.65f)); moveTo(track(.8f)); moveTo(track(.4f)); up()
        }
        compose.runOnIdle {
            assertEquals(4, sliderChanges.size)
            listOf(65f, 80f, 40f, 40f).zip(sliderChanges).forEach { (expected, actual) ->
                assertEquals(expected, actual, .001f)
            }
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun childTapPreferenceRowTapSemanticsAndKeyboardNeverLayerPulses() {
        fixture()
        switch().performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(pulses.isEmpty()); assertTrue(switchChanges.isEmpty()) }
        switch().performTouchInput { up() }
        val row = compose.onNodeWithText("Haptic switch")
        // The label side exercises the row owner instead of hitting its child.
        row.performTouchInput { click(Offset(width * .2f, centerY)) }
        row.performClick()
        row.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        row.performKeyInput { pressKey(Key.Spacebar) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(true, false, true, false), switchChanges)
            assertEquals(List(4) { ControlHaptic.Weak }, pulses)
            assertTrue(sliderChanges.isEmpty())
        }
    }

    @Test fun switchOwnedDragTicksOnlyDistinctBooleanPreviewsAndCommitsOnceOnRelease() {
        fixture()
        switch().performTouchInput { down(Offset(width * 16f / 60f, centerY)); moveBy(Offset(width * .2f, 0f)) }
        compose.runOnIdle {
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            assertTrue(switchChanges.isEmpty())
            checked = true; reduced = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        switch().performTouchInput { moveBy(Offset(width * .2f, 0f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses); assertTrue(switchChanges.isEmpty()) }
        switch().performTouchInput { moveBy(Offset(width * .02f, 0f)) }
        compose.runOnIdle { assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
        switch().performTouchInput { moveBy(Offset(-width * .37f, 0f)); up() }
        compose.runOnIdle {
            assertEquals(listOf(false), switchChanges)
            assertFalse(checked)
            assertEquals(List(3) { ControlHaptic.Weak } + ControlHaptic.Strong, pulses)
        }
    }

    @Test fun switchInitialJumpEngagesOnceAndUnchangedReleaseHasNoExtraActivation() {
        fixture()
        switch().performTouchInput {
            down(Offset(width * 16f / 60f, centerY)); moveBy(Offset(width * .6f, 0f))
        }
        compose.runOnIdle { assertTrue(switchChanges.isEmpty()); assertEquals(listOf(ControlHaptic.Weak), pulses) }
        switch().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(true), switchChanges)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun pendingCancellationAndSecondPointersStaySilentAndCannotGhostToggleTheRow() {
        fixture()
        for (node in listOf(slider(), switch())) {
            node.performTouchInput { down(center); moveBy(Offset(1f, 1f)); cancel() }
            node.performTouchInput { down(0, center); down(1, center + Offset(2f, 0f)); up(0); up(1) }
        }
        compose.runOnIdle {
            assertTrue(sliderChanges.isEmpty()); assertTrue(switchChanges.isEmpty()); assertTrue(pulses.isEmpty())
        }
        switch().performTouchInput { click() }
        slider().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(true), switchChanges); assertEquals(listOf(.5f), sliderChanges)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun ownedCancellationNeverEmitsReleaseOrSwitchCommitAndNextTapIsIndependent() {
        fixture()
        slider().performTouchInput { down(center); moveTo(track(.75f)); cancel() }
        switch().performTouchInput { down(center); moveBy(Offset(width * .6f, 0f)); cancel() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(.75f), sliderChanges)
            assertEquals(.75f, value)
            assertTrue(switchChanges.isEmpty()); assertFalse(checked)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
        switch().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(true), switchChanges); assertEquals(List(3) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun disabledSliderAndCallbacklessSwitchIgnoreTouchAndAccessibleAdjustment() {
        enabled = false
        fixture(preference = false)
        slider().assertIsNotEnabled().performTouchInput { click(); swipe(center, track(.9f), 300) }
        slider().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(.75f)) }
        switch().performTouchInput { click(); swipe(center, Offset(width.toFloat(), centerY), 300) }
        compose.runOnIdle {
            assertTrue(sliderChanges.isEmpty()); assertTrue(switchChanges.isEmpty()); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun disablingOwnedControlsCancelsFeedbackInsteadOfPretendingToRelease() {
        fixture(preference = false)
        slider().performTouchInput { down(center); moveTo(track(.75f)) }
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        slider().performTouchInput { up() }
        compose.runOnIdle { enabled = true }
        switch().performTouchInput { down(center); moveBy(Offset(width * .6f, 0f)) }
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        switch().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(.75f), sliderChanges); assertTrue(switchChanges.isEmpty()); assertFalse(checked)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun programmaticValuesSettlingReducedMotionAndAvailabilityRemainSilent() {
        fixture(preference = false)
        compose.runOnIdle { value = 1f; checked = true }
        compose.waitForIdle()
        compose.runOnIdle { value = 0f; checked = false; enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { reduced = true; value = .75f; checked = true; enabled = true }
        compose.waitForIdle()
        compose.runOnIdle { reduced = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sliderChanges.isEmpty()); assertTrue(switchChanges.isEmpty()); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun verticalParentScrollStaysSilentThroughAxialReversalForBothControls() {
        fixture(parent = Parent.Vertical)
        for (node in listOf(slider(), switch())) {
            node.performTouchInput {
                down(center)
                moveBy(Offset(0f, -80f), delayMillis = 16)
                moveBy(Offset(0f, -40f), delayMillis = 16)
                moveBy(Offset(width.toFloat(), 0f), delayMillis = 160)
                up()
            }
            compose.runOnIdle {
                assertTrue("The actual parent must scroll", scroll.value > 0)
                assertTrue(sliderChanges.isEmpty()); assertTrue(switchChanges.isEmpty()); assertTrue(pulses.isEmpty())
                scroll.dispatchRawDelta(-scroll.value.toFloat())
            }
        }
    }

    @Test fun sameAxisFastSweepsScrollSilentlyButHeldAdjustmentsOwnOneFeedbackLifecycle() {
        fixture(parent = Parent.Horizontal)
        for (node in listOf(slider(), switch())) {
            node.performTouchInput { swipe(center, center + Offset(-160f, 0f), 120) }
            compose.runOnIdle {
                assertTrue("The actual same-axis parent must scroll", scroll.value > 0)
                assertTrue(sliderChanges.isEmpty()); assertTrue(switchChanges.isEmpty()); assertTrue(pulses.isEmpty())
                scroll.dispatchRawDelta(-scroll.value.toFloat())
            }
        }
        slider().performTouchInput { down(center); advanceEventTime(200); moveTo(track(.75f), delayMillis = 100); up() }
        switch().performTouchInput {
            down(Offset(width * 16f / 60f, centerY)); advanceEventTime(200)
            moveBy(Offset(width * .6f, 0f), delayMillis = 100); up()
        }
        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertEquals(listOf(.75f, .75f), sliderChanges); assertEquals(listOf(true), switchChanges)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }
}
