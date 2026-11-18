@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.anastaci1a.localica

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.layout.*
import io.github.anastaci1a.localica.ui.components.rememberNativeViewportRelocation
import io.github.anastaci1a.localica.ui.components.NativeViewportRelocationHandle
import io.github.anastaci1a.localica.ui.engine.NativeViewportRelocation
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Ownership tests use controlled inset input; they are not native-frame or presentation proof. */
class PopupEditorFocusOwnershipTest {
    @get:Rule val compose = localicaComposeRule()
    private val focus = FocusRequester()
    private val nextFocus = FocusRequester()
    private val dispatcher = NestedScrollDispatcher()
    private var current by mutableIntStateOf(0)
    private var source by mutableIntStateOf(0)
    private var target by mutableIntStateOf(0)
    private var height by mutableIntStateOf(400)
    private var shown by mutableStateOf(true)
    private lateinit var clearFocus: () -> Unit
    private lateinit var state: ScrollState
    private lateinit var owner: PopupEditorFocus
    private lateinit var native: NativeViewportRelocationHandle
    private lateinit var scope: CoroutineScope
    private val inherited = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (offset < 0f) offset else (offset + size - containerSize).coerceAtLeast(0f)
    }

    private fun setup(consumeDown: Boolean = false, activate: (() -> Unit)? = null) {
        compose.setContent { LocalicaTheme {
            state = rememberScrollState()
            scope = rememberCoroutineScope()
            val view = LocalView.current
            val focusManager = LocalFocusManager.current
            SideEffect { clearFocus = { focusManager.clearFocus(force = true) } }
            if (shown) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                val handle = rememberNativeViewportRelocation(state, view) { view.isAttachedToWindow && view.hasWindowFocus() }
                native = handle
                owner = remember(handle) { PopupEditorFocus(state,
                    bottomInsets { current }, bottomInsets { source }, bottomInsets { target }, handle) }
                DisposableEffect(owner) { onDispose { owner.dispose() } }
                val ancestor = if (consumeDown) Modifier.pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(false, PointerEventPass.Initial).consume() }
                } else Modifier
                PopupEditorBodySlot(owner, Modifier.controlVerticalScroll(state),
                    ancestor.fillMaxWidth().height(height.dp).testTag("relocation-body")) {
                    Spacer(Modifier.height(450.dp))
                    Box(Modifier.size(48.dp).focusRequester(focus).focusable()
                        .nestedScroll(object : NestedScrollConnection {}, dispatcher)) {
                        if (activate != null) ActionIconButton(activate,
                            Modifier.fillMaxSize().testTag("relocation-action")) { Box(Modifier.size(24.dp)) }
                    }
                    Spacer(Modifier.height(700.dp))
                    Box(Modifier.size(48.dp).focusRequester(nextFocus).focusable())
                    Spacer(Modifier.height(300.dp))
                }
                }
            }
        } }
        compose.runOnIdle { assertTrue(focus.requestFocus()) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.value > 0) }
    }

    @Test fun activeDefaultFlingKeepsItsMutationAcrossNativeResizeAndDoesNotReplay() = flingCase(false)
    @Test fun cancelledFlingWithoutPostCallbackReleasesOnlyItsFence() = flingCase(true)

    @Test fun stationaryButtonTapDuringPendingNativePlacementActivates() {
        var activations = 0
        setup { activations++ }
        val button = compose.onNodeWithTag("relocation-action").assertIsDisplayed()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput { down(center); up() }
        var scroll = 0
        compose.runOnIdle {
            assertEquals("The same physical button activates before native ownership", 1, activations)
            assertFalse(state.isScrollInProgress)
            scroll = state.value
            target = 200
        }
        assertEquals("Changing only the pending inset target leaves the button stationary",
            bounds, button.fetchSemanticsNode().boundsInRoot)
        compose.runOnUiThread {
            assertEquals(NativeRelocationResult.Adjusting, native.result)
            assertTrue("The native owner holds the mutation while awaiting the next placement", state.isScrollInProgress)
            assertEquals(scroll, state.value)
            println("Pending native tap: bounds=$bounds scroll=$scroll native=${native.result} progress=${state.isScrollInProgress}")
        }
        button.performTouchInput { down(center); up() }
        assertEquals("The button remains stationary through physical DOWN and UP",
            bounds, button.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            println("After native tap: activations=$activations scroll=${state.value} native=${native.result} progress=${state.isScrollInProgress}")
            assertEquals("A stationary contact must not move the body", scroll, state.value)
            assertEquals("The in-bounds button tap must activate while native placement is pending", 2, activations)
        }
    }

    @Test fun heldContactKeepsNativeFramesFencedUntilTheLastPointer() {
        setup()
        val body = compose.onNodeWithTag("relocation-body")
        val scroll = compose.runOnIdle { state.value }
        body.performTouchInput { down(0, center); down(1, center + Offset(20f, 0f)) }
        compose.runOnIdle { target = 200; current = 100 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(NativeRelocationResult.Superseded, native.result)
            assertEquals(scroll, state.value)
            assertFalse(state.isScrollInProgress)
        }
        body.performTouchInput { up(0) }
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("The remaining pointer still owns the settled frame", NativeRelocationResult.Superseded, native.result)
            assertEquals(scroll, state.value)
        }
        body.performTouchInput { up(1) }
        compose.runOnIdle {
            assertEquals(NativeRelocationResult.Ordinary, native.result)
            assertEquals("Last UP cannot replay native correction", scroll, state.value)
            target = 300
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("A later native change has no orphan contact fence", NativeRelocationResult.Adjusting, native.result)
            assertTrue(state.isScrollInProgress)
        }
    }

    @Test fun cancelledAndDetachedContactsReleaseTheirOwnership() {
        setup()
        val body = compose.onNodeWithTag("relocation-body")
        body.performTouchInput { down(center) }
        compose.runOnIdle { target = 200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(NativeRelocationResult.Superseded, native.result) }
        body.performTouchInput { cancel() }
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(NativeRelocationResult.Ordinary, native.result); target = 300 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isScrollInProgress) }
        body.performTouchInput { down(center) }
        val detached = compose.runOnIdle {
            assertEquals(NativeRelocationResult.Superseded, native.result)
            assertFalse(state.isScrollInProgress)
            shown = false
            native
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(NativeRelocationResult.Disposed, detached.result); assertFalse(state.isScrollInProgress) }
        compose.onRoot().performTouchInput { cancel() }
        compose.runOnIdle { current = 300; source = 300; shown = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(focus.requestFocus()) }
        compose.waitForIdle()
        compose.runOnIdle { target = 400 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(NativeRelocationResult.Adjusting, native.result); assertTrue(state.isScrollInProgress) }
    }

    @Test fun ancestorConsumedDownYieldsNativeLeaseWithoutActivatingChild() {
        var activations = 0
        setup(consumeDown = true) { activations++ }
        compose.runOnIdle { target = 200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(NativeRelocationResult.Adjusting, native.result); assertTrue(state.isScrollInProgress) }
        val button = compose.onNodeWithTag("relocation-action").assertIsDisplayed()
        button.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(NativeRelocationResult.Superseded, native.result); assertFalse(state.isScrollInProgress) }
        button.performTouchInput { up() }
        compose.runOnIdle { assertEquals("Contact observation cannot unconsume an ancestor's DOWN", 0, activations) }
    }

    @Test fun physicalDragFlingKeepsNativeFramesSupersededAcrossRelease() {
        setup()
        val body = compose.onNodeWithTag("relocation-body")
        compose.mainClock.autoAdvance = false
        try {
            body.performTouchInput { down(center); moveBy(Offset(0f, -80f), delayMillis = 16) }
            compose.runOnUiThread { assertTrue(state.isScrollInProgress); target = 200; current = 100 }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val held = compose.runOnUiThread { assertEquals(NativeRelocationResult.Superseded, native.result); state.value }
            compose.runOnUiThread { current = 160 }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { assertEquals("No native write while contact is held", held, state.value) }
            body.performTouchInput { repeat(4) { moveBy(Offset(0f, -20f), delayMillis = 16) }; up() }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("Physical release must start a real fling", state.isScrollInProgress)
                assertEquals(NativeRelocationResult.Superseded, native.result)
                current = 200; source = 200
            }
            var before = compose.runOnUiThread { state.value }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread {
                    assertTrue("Native completion cannot cancel the real fling", state.isScrollInProgress)
                    assertEquals(NativeRelocationResult.Superseded, native.result)
                    assertTrue("Fling continues forward without native catch-up", state.value >= before)
                    before = state.value
                }
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val stopped = compose.runOnIdle { assertFalse(state.isScrollInProgress); state.value }
            repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle {
                assertEquals(NativeRelocationResult.Ordinary, native.result)
                assertEquals("A completed fling must not replay the superseded correction", stopped, state.value)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun buttonTapDuringRealUserFlingStopsWithoutActivation() {
        var activations = 0
        setup { activations++ }
        val button = compose.onNodeWithTag("relocation-action").assertIsDisplayed()
        button.performTouchInput { down(center); up() }
        compose.runOnIdle { assertEquals(1, activations) }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("relocation-body").performTouchInput {
                swipeWithVelocity(center, center - Offset(0f, 160f), endVelocity = 1800f, durationMillis = 120)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val before = compose.runOnUiThread { state.value }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("The physical release must still be flinging", state.isScrollInProgress)
                assertTrue("The fling has moved across frames", state.value > before)
                assertEquals("This is ordinary user scrolling, not native correction", NativeRelocationResult.Ordinary, native.result)
            }
            button.assertIsDisplayed().performTouchInput { down(center); up() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                assertFalse("The tap interrupts the real user fling", state.isScrollInProgress)
                assertEquals("A fling-interruption tap must not activate the button", 1, activations)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun flingCase(cancel: Boolean) {
        setup()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        var value = -1
        lateinit var user: Job
        compose.runOnIdle {
            user = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.dispatchPreFling(Velocity(0f, 1000f))
                try {
                    state.scroll(MutatePriority.Default) { admitted.complete(Unit); release.await() }
                    dispatcher.dispatchPostFling(Velocity(0f, 1000f), Velocity.Zero)
                } finally { cancelled = currentCoroutineContext().job.isCancelled }
            }
            value = state.value
        }
        compose.waitUntil { admitted.isCompleted }
        compose.runOnIdle { target = 200; current = 160; height = 200 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("IME adjustment must not cancel a user Default fling", user.isActive)
            assertFalse(cancelled)
            assertEquals("No second writer behind the user's mutation", value, state.value)
            if (cancel) user.cancel() else release.complete(Unit)
        }
        compose.waitUntil { user.isCompleted }
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("A superseded adjustment must not replay", value, state.value)
            assertSame(inherited.scrollAnimationSpec, owner.bodySpec.scrollAnimationSpec)
            assertEquals(inherited.calculateScrollDistance(250f, 40f, 200f),
                owner.bodySpec.calculateScrollDistance(250f, 40f, 200f), .01f)
        }
    }

    @Test fun disposingBodyDoesNotCancelAUserMutationOrReplayCorrection() {
        setup()
        val release = CompletableDeferred<Unit>()
        lateinit var user: Job
        compose.runOnIdle {
            user = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.dispatchPreFling(Velocity(0f, 1000f))
                state.scroll(MutatePriority.Default) { release.await() }
            }
            target = 200; current = 100; height = 200
        }
        compose.waitForIdle()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(user.isActive); release.complete(Unit) }
        compose.waitUntil { user.isCompleted }
    }

    @Test fun losingFocusDuringResizeRestoresOrdinaryPolicyAtNativeEnd() {
        setup()
        compose.runOnIdle { target = 200; current = 100; height = 200 }
        compose.waitForIdle()
        compose.runOnIdle { clearFocus() }
        compose.waitForIdle()
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Focus loss cannot leave a permanent zero-distance body policy",
                inherited.calculateScrollDistance(250f, 40f, 200f),
                owner.bodySpec.calculateScrollDistance(250f, 40f, 200f), .01f)
        }
    }

    @Test fun nextOrdinaryFocusMoveUsesInheritedEasingAfterImeReconciliation() {
        setup()
        compose.runOnIdle { target = 200; current = 100; height = 200 }
        compose.waitForIdle()
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        var before = 0
        compose.runOnIdle { before = state.value }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { assertTrue(nextFocus.requestFocus()) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            var during = 0
            compose.runOnUiThread { during = state.value }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Ordinary focus movement must start again", during > before)
                assertTrue("The inherited easing must retain intermediate placement", state.value > during)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun hideWithVisibleFocusKeepsTheExistingActualScrollPosition() {
        setup()
        compose.runOnIdle { target = 200; current = 100; height = 200 }
        compose.waitForIdle()
        compose.runOnIdle { current = 200; source = 200 }
        compose.waitForIdle()
        var before = 0
        compose.runOnIdle { before = state.value; target = 0; current = 100; height = 400 }
        compose.waitForIdle()
        compose.runOnIdle { current = 0; source = 0 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Visible focus must not rewind on hide", before, state.value) }
    }

    @Test fun actualHeldDragSupersedesResizeAndNeverGetsACatchUpCorrection() {
        setup()
        compose.onNodeWithTag("relocation-body").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 80f))
        }
        compose.waitForIdle()
        var dragged = 0
        compose.runOnIdle { assertTrue(state.isScrollInProgress); dragged = state.value; target = 200; current = 100; height = 200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Native resize cannot write behind the held drag", dragged, state.value)
            assertEquals(0f, owner.bodySpec.calculateScrollDistance(250f, 40f, 200f), .01f) }
        compose.onNodeWithTag("relocation-body").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { dragged = state.value; current = 200; source = 200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("A cancelled adjustment cannot replay after input", dragged, state.value) }
    }

    @Test fun saturatedSettledCaretTerminatesPendingRequestWithoutStaleForwarding() {
        val window = NativeRelocationWindow()
        lateinit var relocation: NativeViewportRelocation
        compose.setContent { LocalicaTheme {
            state = rememberScrollState()
            scope = rememberCoroutineScope()
            relocation = remember { NativeViewportRelocation(state, scope, window, inherited) { true } }
            DisposableEffect(relocation) { onDispose { relocation.close() } }
            Column(Modifier.fillMaxWidth().height(200.dp).controlVerticalScroll(state)) {
                Spacer(Modifier.height(1200.dp))
            }
        } }
        lateinit var request: Job
        var forwarded = 0
        var restoredAtTermination = false
        var saturated = -1
        compose.runOnIdle {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { state.scrollTo(state.maxValue) }
            saturated = state.value
            assertEquals(state.maxValue, saturated)
            relocation.focusChanged(1, true)
            relocation.native(NativeRelocationFrame(window, 1, 0, 1, true, 0, 0, 0))
            relocation.native(NativeRelocationFrame(window, 2, 1, 1, true, 100, 0, 200))
            request = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { relocation.request(1, 1) { forwarded++ } }
                finally { restoredAtTermination = relocation.bodyPolicy.calculateScrollDistance(250f, 40f, 200f) == 90f }
            }
            assertTrue("The request must really be pending before terminal placement", request.isActive)
            val final = NativeRelocationFrame(window, 3, 2, 1, true, 200, 200, 200)
            val caret = NativeRelocationTarget(state.viewportSize + 20f, state.viewportSize + 62f)
            println("Saturated pending request: scroll=$saturated max=${state.maxValue} viewport=${state.viewportSize} caret=$caret")
            relocation.native(final)
            relocation.placed(NativeRelocationPlacement(final, saturated, state.viewportSize, caret))
        }
        compose.waitUntil(timeoutMillis = 2000) { request.isCompleted }
        compose.runOnIdle {
            assertTrue("Unreachable requests terminate by cancellation", request.isCancelled)
            assertEquals(NativeRelocationResult.Unreachable, relocation.result)
            assertTrue("Ordinary policy must be restored before the waiter terminates", restoredAtTermination)
            assertEquals(0, forwarded)
            assertFalse("The terminal request cannot later forward stale bounds", relocation.currentRequest(1, 1))
            assertEquals("No saturated correction may write or rewind", saturated, state.value)
        }
    }

    private fun bottomInsets(bottom: () -> Int) = object : WindowInsets {
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom()
    }
}
