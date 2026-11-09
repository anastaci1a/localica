@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.anastaci1a.localica

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.layout.*
import io.github.anastaci1a.localica.ui.components.rememberNativeViewportRelocation
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
    private lateinit var scope: CoroutineScope
    private val inherited = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (offset < 0f) offset else (offset + size - containerSize).coerceAtLeast(0f)
    }

    private fun setup() {
        compose.setContent { LocalicaTheme {
            state = rememberScrollState()
            scope = rememberCoroutineScope()
            val view = LocalView.current
            val focusManager = LocalFocusManager.current
            SideEffect { clearFocus = { focusManager.clearFocus(force = true) } }
            if (shown) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                val handle = rememberNativeViewportRelocation(state, view) { view.isAttachedToWindow && view.hasWindowFocus() }
                owner = remember(handle) { PopupEditorFocus(state,
                    bottomInsets { current }, bottomInsets { source }, bottomInsets { target }, handle) }
                DisposableEffect(owner) { onDispose { owner.dispose() } }
                PopupEditorBodySlot(owner, Modifier.controlVerticalScroll(state),
                    Modifier.fillMaxWidth().height(height.dp).testTag("relocation-body")) {
                    Spacer(Modifier.height(450.dp))
                    Box(Modifier.size(48.dp).focusRequester(focus).focusable()
                        .nestedScroll(object : NestedScrollConnection {}, dispatcher))
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

    private fun bottomInsets(bottom: () -> Int) = object : WindowInsets {
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom()
    }
}
