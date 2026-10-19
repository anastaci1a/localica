package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeekCancelMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enterAndExitShareEasingAndReversalsContinueFromVisibleProgress() {
        var over by mutableStateOf(false)
        var entering = 80f
        var leaving = 20f
        compose.setContent {
            entering = seekCancelPreview(80f, 20f, over)
            leaving = seekCancelPreview(80f, 20f, !over)
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { over = true }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertTrue(entering in 21f..79f)
            assertTrue(leaving in 21f..79f)
            assertEquals("Same elapsed ease in either direction", 100f, entering + leaving, .001f)
            over = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("Reversal must not snap to finger", entering in 21f..79f) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { over = true }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(20f, entering, .001f); assertEquals(80f, leaving, .001f) }
    }

    @Test fun movingEndpointsDoNotRestartReturnOrLeaveOrdinaryFingerTrackingBehind() {
        var over by mutableStateOf(true)
        var finger by mutableFloatStateOf(80f)
        var live by mutableFloatStateOf(20f)
        var shown = 0f
        compose.setContent { shown = seekCancelPreview(finger, live, over) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { live = 25f }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("Playback stays live while hovering", 25f, shown, .001f); over = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertTrue(shown > live && shown < finger) }
        repeat(6) {
            compose.runOnIdle { finger += 2f; live += 1f }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.runOnIdle {
            assertEquals("Moving targets must not prolong or snap an unfinished return", finger, shown, .001f)
            finger = 40f
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("Normal scrubbing remains immediate", finger, shown, .001f) }
    }

    @Test fun fasterMotionShortensBothDirectionsAndReducedMotionSnaps() {
        var over by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        var normal = 0f
        var faster = 0f
        var fasterExit = 1f
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                normal = seekCancelPreview(0f, 1f, over)
                CompositionLocalProvider(LocalMotionSpeed provides 2f) {
                    faster = seekCancelPreview(0f, 1f, over)
                    fasterExit = seekCancelPreview(0f, 1f, !over)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { over = true }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            assertEquals(1f, faster, .001f)
            assertEquals(0f, fasterExit, .001f)
            assertTrue("Normal transition still runs after Faster completes", normal in .01f.. .999f)
            reduced = true
            over = false
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(0f, normal, .001f); assertEquals(0f, faster, .001f); assertEquals(1f, fasterExit, .001f) }
    }

    @Test fun freshScrubImmediatelyTracksFingerAfterCancelReleaseOrPointerCancellation() {
        var finger by mutableFloatStateOf(20f)
        compose.setContent {
            // Match the explicit dark background: the seek track inherits its caller's ink.
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PlayerSeekBar(finger, { finger = it }, 0f..100f, {}, true,
                        Modifier.width(300.dp).height(40.dp).testTag("seek"),
                        cancelBounds = Rect(0f, 100f, 1000f, 1000f),
                        onCancel = { finger = 20f }, liveValue = 20f)
                }
            }
        }
        val seek = compose.onNodeWithTag("seek")
        fun brightAtSixtyPercent(): Boolean {
            val pixels = seek.captureToImage().toPixelMap()
            return pixels[(pixels.width * .6f).toInt(), pixels.height / 2].red > .5f
        }
        for (pointerCancel in listOf(false, true)) {
            seek.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
            assertTrue("Ordinary scrub supplies a visible foreground signal", brightAtSixtyPercent())
            seek.performTouchInput { moveTo(Offset(width * .8f, 200f)) }
            assertFalse("Settled hover displays live playback", brightAtSixtyPercent())
            seek.performTouchInput { if (pointerCancel) cancel() else up() }
            compose.mainClock.autoAdvance = false
            seek.performTouchInput { down(Offset(width * .8f, centerY)); moveTo(Offset(width * .9f, centerY)) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("New scrub must not inherit the old cancellation tween", brightAtSixtyPercent())
            seek.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun hoverReentryReleaseAndPointerCancellationNeverCommitStalePreview() {
        var finger by mutableFloatStateOf(20f)
        val commits = mutableListOf<Float>()
        var cancellations = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                PlayerSeekBar(finger, { finger = it }, 0f..100f,
                    { commits += finger }, true,
                    Modifier.width(300.dp).height(40.dp).testTag("seek"),
                    cancelBounds = Rect(0f, 100f, 1000f, 1000f),
                    onCancel = { cancellations++; finger = 20f }, liveValue = 20f)
            }
        }
        val seek = compose.onNodeWithTag("seek")
        seek.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
        seek.performTouchInput { moveTo(Offset(width * .8f, 200f)) }
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        seek.performTouchInput { moveTo(Offset(width * .6f, centerY)) }
        seek.performTouchInput { moveTo(Offset(width * .6f, 200f)); up() }
        compose.runOnIdle { assertTrue(commits.isEmpty()); assertTrue(cancellations > 0) }
        seek.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)); cancel() }
        compose.runOnIdle { assertTrue("Pointer cancellation does not seek", commits.isEmpty()) }
        seek.performTouchInput { down(center); moveTo(Offset(width * .8f, 200f)) }
        seek.performTouchInput { moveTo(Offset(width * .7f, centerY)) }
        var selected = 0f
        compose.runOnIdle { selected = finger; assertTrue(commits.isEmpty()) }
        seek.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(selected), commits) }
        seek.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.runOnIdle { assertEquals(listOf(selected, 40f), commits) }
        seek.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)); cancel() }
        compose.runOnIdle { assertEquals("Second cancellation still cannot seek", 2, commits.size) }
    }
}
