package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.QueueSurface
import io.github.anastaci1a.localica.ui.pinchToChangeView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueScrollOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val progress = Animatable(1f)
    private lateinit var list: LazyListState

    private fun showQueue(initialIndex: Int = 0, onPinchRelease: ((Boolean) -> Unit)? = null) {
        compose.setContent { LocalicaTheme {
            list = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                QueueSurface(progress, InterfaceSettings(reducedMotion = true), {}) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("ownership-list")
                        .then(if (onPinchRelease != null) Modifier.pinchToChangeView(true,
                            onProgress = {}, onRelease = onPinchRelease, onStep = { _, _ -> true }) else Modifier), state = list) {
                        items(60) { Text("Song $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                }
            }
        } }
    }

    @Test fun reachingTopMidContactKeepsDrawerStillIncludingReversalAndNextFreshPull() {
        showQueue(initialIndex = 1)
        val songs = compose.onNodeWithTag("ownership-list")
        songs.performTouchInput {
            down(Offset(centerX, height * .15f))
            repeat(10) { moveBy(Offset(0f, height * .055f), delayMillis = 40) }
        }
        compose.runOnIdle {
            assertFalse(list.canScrollBackward)
            assertEquals("Scrolling to the top must not move the drawer", 1f, progress.value, .001f)
        }
        songs.performTouchInput {
            repeat(3) { moveBy(Offset(0f, -height * .055f), delayMillis = 40) }
            repeat(5) { moveBy(Offset(0f, height * .055f), delayMillis = 40) }
            advanceEventTime(200); up()
        }
        compose.runOnIdle {
            assertFalse(list.canScrollBackward)
            assertEquals(1f, progress.value, .001f)
        }
        // A separate downward contact at the top retains the intentional drawer gesture.
        songs.performTouchInput {
            swipe(Offset(centerX, height * .15f), Offset(centerX, height * .7f), 800)
        }
        compose.runOnIdle { assertEquals(0f, progress.value, .001f) }
    }

    @Test fun flingAtTheTopDoesNotPullTheDrawerAndHandleStillClosesIt() {
        showQueue(initialIndex = 2)
        compose.onNodeWithTag("ownership-list").performTouchInput {
            swipe(Offset(centerX, height * .1f), Offset(centerX, height * .85f), 90)
        }
        compose.runOnIdle { assertEquals(1f, progress.value, .001f) }
        compose.onNodeWithContentDescription("Close queue").performTouchInput {
            swipe(center, center + Offset(0f, 100f), 60)
        }
        compose.runOnIdle { assertEquals(0f, progress.value, .001f) }
    }

    @Test fun cancelledPullRestoresDrawerAndANewContactCanPullAgain() {
        showQueue()
        val songs = compose.onNodeWithTag("ownership-list")
        songs.performTouchInput {
            down(Offset(centerX, height * .15f))
            repeat(4) { moveBy(Offset(0f, height * .05f), delayMillis = 60) }
        }
        compose.runOnIdle { assertTrue(progress.value < 1f) }
        songs.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(1f, progress.value, .001f) }
        songs.performTouchInput {
            swipe(Offset(centerX, height * .15f), Offset(centerX, height * .75f), 800)
        }
        compose.runOnIdle { assertEquals(0f, progress.value, .001f) }
    }

    @Test fun heldPullStillCommitsOnRealReleaseAfterVelocityHasExpired() {
        showQueue()
        val songs = compose.onNodeWithTag("ownership-list")
        songs.performTouchInput {
            down(Offset(centerX, height * .15f))
            repeat(4) { moveBy(Offset(0f, height * .05f), delayMillis = 60) }
        }
        compose.runOnIdle { assertTrue(progress.value < 1f) }
        songs.performTouchInput { advanceEventTime(300); up() }
        compose.runOnIdle { assertEquals(0f, progress.value, .001f) }
    }

    @Test fun secondPointerCancelsPullAndCannotRestartItUntilAllPointersLift() {
        showQueue()
        val songs = compose.onNodeWithTag("ownership-list")
        songs.performTouchInput {
            down(0, Offset(centerX, height * .15f))
            repeat(3) { moveBy(0, Offset(0f, height * .05f), delayMillis = 60) }
        }
        compose.runOnIdle { assertTrue(progress.value < 1f) }
        songs.performTouchInput {
            down(1, Offset(centerX + 30f, height * .3f))
            moveBy(0, Offset(0f, 20f), delayMillis = 60)
            up(1)
            moveBy(0, Offset(0f, 80f), delayMillis = 80)
            advanceEventTime(200); up(0)
        }
        compose.runOnIdle { assertEquals(1f, progress.value, .001f) }
    }

    @Test fun childPinchStillCompletesAfterSingleFingerListScrolling() {
        var released: Boolean? = null
        showQueue(initialIndex = 2, onPinchRelease = { released = it })
        compose.onNodeWithTag("ownership-list").performTouchInput {
            down(0, Offset(centerX - 50f, height * .5f))
            moveBy(0, Offset(0f, -60f), delayMillis = 100)
            down(1, Offset(centerX + 50f, height * .5f - 60f))
            repeat(4) {
                moveBy(0, Offset(-15f, 0f), delayMillis = 40)
                moveBy(1, Offset(15f, 0f), delayMillis = 40)
            }
            up(1); up(0)
        }
        compose.runOnIdle {
            assertEquals("The outer scroll owner must not cancel its child's pinch", false, released)
            assertEquals(1f, progress.value, .001f)
        }
    }
}
