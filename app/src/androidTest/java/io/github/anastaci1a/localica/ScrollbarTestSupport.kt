package io.github.anastaci1a.localica

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec

/** A faded scrollbar has no hit area (#704), and an idle fixture fades it. Present it as a user scroll
 * does: nudge the list by one pixel and play the fade-in. The clock stays paused so the idle delay does
 * not hide it again; callers step frames themselves and restore [mainClock] auto-advance when done.
 */
internal fun ComposeContentTestRule.revealScrollbar(state: ScrollableState) {
    waitForIdle()
    mainClock.autoAdvance = false
    runOnIdle { state.dispatchRawDelta(if (state.canScrollForward) 1f else -1f) }
    mainClock.advanceTimeBy(ScrollbarSpec.fadeMillis + 64L)
    waitForIdle()
}

/** [revealScrollbar] for a list known only by its test tag: a short real drag moves it as a user would. */
internal fun ComposeContentTestRule.revealScrollbar(listTag: String) {
    waitForIdle()
    mainClock.autoAdvance = false
    onNodeWithTag(listTag).performTouchInput {
        down(center); moveBy(Offset(0f, -viewConfiguration.touchSlop * 4), delayMillis = 200); up()
    }
    mainClock.advanceTimeBy(ScrollbarSpec.fadeMillis + 64L)
    waitForIdle()
}

/** The scrollbar strip's local y of the thumb's centre: where a finger grabs it. */
internal fun ComposeContentTestRule.thumbGrabY(): Float =
    onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.center.y -
        onNodeWithTag("browse-scrollbar").fetchSemanticsNode().boundsInRoot.top
