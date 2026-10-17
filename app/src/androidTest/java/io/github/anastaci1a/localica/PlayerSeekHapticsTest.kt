package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
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
class PlayerSeekHapticsTest {
    @get:Rule val compose = createComposeRule()
    private val pulses = mutableListOf<ControlHaptic>()
    private val sink = ControlHaptics { pulses += it }
    private val changes = mutableListOf<Float>()
    private val commits = mutableListOf<Float>()
    private var cancellations = 0
    private var value by mutableFloatStateOf(50f)
    private var liveValue by mutableFloatStateOf(50f)
    private var enabled by mutableStateOf(true)
    private var reduced by mutableStateOf(false)
    private var cancelBounds by mutableStateOf<Rect?>(null)
    private var consumeUp by mutableStateOf(false)
    private var consumedUps = 0
    private lateinit var scroll: ScrollState
    private lateinit var inputMode: InputModeManager

    private fun fixture(verticalParent: Boolean = false) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides sink, LocalReducedMotion provides reduced) {
                inputMode = LocalInputModeManager.current
                scroll = rememberScrollState()
                // An ancestor can consume a release before Slider observes it. This exercises
                // the same consumed-up exclusion as a synthetic pointer cancellation.
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (consumeUp) event.changes.forEach { change ->
                                if (change.previousPressed && !change.pressed) {
                                    change.consume()
                                    consumedUps++
                                }
                            }
                        }
                    }
                }) {
                    val seek: @Composable () -> Unit = {
                        PlayerSeekBar(value, { value = it; changes += it }, 0f..100f,
                            { commits += value }, enabled,
                            Modifier.width(300.dp).height(40.dp).testTag("haptic-seek"),
                            cancelBounds = cancelBounds,
                            onCancel = { cancellations++; value = liveValue }, liveValue = liveValue)
                    }
                    if (verticalParent) {
                        Column(Modifier.width(300.dp).height(450.dp).controlVerticalScroll(scroll)) {
                            Spacer(Modifier.height(100.dp))
                            seek()
                            Spacer(Modifier.height(900.dp))
                        }
                    } else seek()
                }
            }
        } }
    }

    private fun seek() = compose.onNodeWithTag("haptic-seek")
    private fun TouchInjectionScope.track(fraction: Float) = Offset(width * fraction, centerY)

    private fun installCancelTarget(): Offset {
        val bounds = seek().fetchSemanticsNode().boundsInRoot
        val target = Rect(bounds.left, bounds.bottom + 40f, bounds.right, bounds.bottom + 140f)
        compose.runOnIdle { cancelBounds = target }
        return target.center - bounds.topLeft
    }

    @Test fun ownedContinuousDragEngagesOnceAndReleasesOnceWithoutValueTicks() {
        fixture()
        seek().performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(pulses.isEmpty()); assertTrue(commits.isEmpty()) }
        seek().performTouchInput { moveTo(track(.7f)) }
        compose.runOnIdle {
            assertTrue("Material must actually adjust the seek", changes.isNotEmpty())
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            assertTrue(commits.isEmpty())
        }
        seek().performTouchInput { moveTo(track(.85f)); moveTo(track(.4f)); moveTo(track(.42f)) }
        var selected = 0f
        compose.runOnIdle {
            assertTrue(changes.size > 1)
            selected = value
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            assertTrue(commits.isEmpty())
        }
        seek().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(selected), commits)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun clampedOwnedDragStillEngagesEvenWhenMaterialHasNoNewValueToPublish() {
        value = 100f
        liveValue = 100f
        fixture()
        seek().performTouchInput {
            down(track(.99f)); moveTo(track(1.15f)); moveTo(track(1.25f))
        }
        compose.runOnIdle {
            assertTrue("Clamping must not invent a value callback", changes.isEmpty())
            assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
        seek().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(100f), commits)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun uninterruptedScrubStillProducesOneOwnedFeedbackLifecycle() {
        fixture()
        seek().performTouchInput { swipe(center, track(.8f), durationMillis = 240) }
        compose.runOnIdle {
            assertTrue(changes.isNotEmpty())
            assertEquals(1, commits.size)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun tapAndSameValueTapEachFinishWithOneWeakPulse() {
        fixture()
        seek().performTouchInput { down(track(.75f)) }
        compose.runOnIdle { assertTrue(pulses.isEmpty()); assertTrue(commits.isEmpty()) }
        seek().performTouchInput { up() }
        var selected = 0f
        var changesAfterTap = 0
        compose.runOnIdle {
            selected = value
            changesAfterTap = changes.size
            assertEquals(listOf(selected), commits)
            assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
        seek().performTouchInput { click(track(.75f)) }
        compose.runOnIdle {
            assertEquals(selected, value, .001f)
            assertEquals("Same-value activation needs no change callback", changesAfterTap, changes.size)
            assertEquals(listOf(selected, selected), commits)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun accessibleActualChangesPulseOnceAndUnchangedOrClampedNoOpsReturnFalseSilently() {
        fixture()
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(50f)) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(70f)) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(70f)) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(200f)) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(200f)) }
        compose.runOnIdle {
            assertEquals(listOf(70f, 100f), changes)
            assertEquals(listOf(70f, 100f), commits)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun unrelatedKeyboardInputWithActualKeyboardFocusRemainsSilent() {
        fixture()
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.waitForIdle()
        seek().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        seek().assertIsFocused().performKeyInput { pressKey(Key.A) }
        compose.runOnIdle {
            assertTrue(changes.isEmpty()); assertTrue(commits.isEmpty()); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun cancelTargetEnterExitAndReentryPulseOncePerBoundaryAndReleaseOnXAddsNothing() {
        fixture()
        val cancel = installCancelTarget()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        seek().performTouchInput { moveTo(cancel); moveTo(cancel + Offset(1f, 1f)) }
        compose.runOnIdle { liveValue = 55f; reduced = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses) }
        seek().performTouchInput { moveTo(track(.6f)) }
        compose.runOnIdle {
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong, ControlHaptic.Weak), pulses)
        }
        seek().performTouchInput { moveTo(cancel); up() }
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertTrue("Existing cancellation callbacks remain available", cancellations > 0)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong,
                ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun leavingXThenGenuineReleaseAddsExactlyOneStrongCommitPulse() {
        fixture()
        val cancel = installCancelTarget()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        seek().performTouchInput { moveTo(cancel) }
        seek().performTouchInput { moveTo(track(.6f)) }
        var selected = 0f
        compose.runOnIdle { selected = value; assertTrue(commits.isEmpty()) }
        seek().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(selected), commits)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong,
                ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun ownedPointerCancellationHasNoReleasePulseAndNextTapIsIndependent() {
        fixture()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        seek().performTouchInput { cancel() }
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertTrue(cancellations > 0)
            assertEquals(listOf(ControlHaptic.Weak), pulses)
        }
        seek().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun pointerCancellationWhileOverXDoesNotAddExitOrReleaseFeedback() {
        fixture()
        val cancel = installCancelTarget()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        seek().performTouchInput { moveTo(cancel); cancel() }
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertTrue(cancellations > 0)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(40f)) }
        compose.runOnIdle {
            assertEquals(listOf(40f), commits)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong, ControlHaptic.Weak), pulses)
        }
    }

    @Test fun consumedUpIsCancellationAndNeverAnOwnedRelease() {
        fixture()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        compose.runOnIdle { consumeUp = true }
        seek().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals("The ancestor must consume the release", 1, consumedUps)
            assertTrue(commits.isEmpty())
            assertTrue(cancellations > 0)
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            consumeUp = false
        }
        seek().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, commits.size); assertEquals(List(2) { ControlHaptic.Weak }, pulses) }
    }

    @Test fun pendingCancellationAndActualVerticalParentScrollRemainSilent() {
        fixture(verticalParent = true)
        seek().performTouchInput { down(center); moveBy(Offset(1f, 1f)); cancel() }
        compose.runOnIdle { assertTrue(changes.isEmpty()); assertTrue(commits.isEmpty()); assertTrue(pulses.isEmpty()) }
        seek().performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f), delayMillis = 16)
            moveBy(Offset(0f, -40f), delayMillis = 16)
            moveBy(Offset(width * .6f, 0f), delayMillis = 160)
            up()
        }
        compose.runOnIdle {
            assertTrue("The real parent must win and scroll", scroll.value > 0)
            assertTrue(changes.isEmpty()); assertTrue(commits.isEmpty()); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun externalPlaybackMotionAndAvailabilityUpdatesStaySilentEvenDuringOwnedDrag() {
        fixture()
        compose.runOnIdle { value = 60f; liveValue = 60f; reduced = true }
        compose.waitForIdle()
        compose.runOnIdle { value = 50f; liveValue = 50f; reduced = false; enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { enabled = true }
        compose.runOnIdle { assertTrue(changes.isEmpty()); assertTrue(commits.isEmpty()); assertTrue(pulses.isEmpty()) }
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        compose.runOnIdle { liveValue = 65f; value = 65f; reduced = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
        seek().performTouchInput { moveTo(track(.7f)); up() }
        compose.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
    }

    @Test fun disabledTouchAndAccessibleAdjustmentCannotSeekOrPulse() {
        enabled = false
        fixture()
        seek().assertIsNotEnabled().performTouchInput { click(); swipe(center, track(.9f), 300) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(75f)) }
        compose.runOnIdle {
            assertEquals(50f, value)
            assertTrue(changes.isEmpty()); assertTrue(commits.isEmpty()); assertTrue(pulses.isEmpty())
        }
    }

    @Test fun disablingAnOwnedDragDoesNotEmitAReleasePulse() {
        fixture()
        seek().performTouchInput { down(center); moveTo(track(.8f)) }
        compose.runOnIdle {
            assertEquals(listOf(ControlHaptic.Weak), pulses)
            enabled = false
        }
        compose.waitForIdle()
        seek().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Weak), pulses) }
    }
}
