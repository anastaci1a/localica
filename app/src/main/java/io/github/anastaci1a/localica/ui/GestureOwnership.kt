package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.unit.Velocity

/** A horizontal child keeps its gesture at either edge instead of forwarding it to the tab pager. */
internal object HorizontalGestureOwner : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

/** Content that has moved keeps this contact, including its remaining edge motion. */
internal class ScrollGestureOwnership {
    private var active = false
    private var handoffBlocked = false
    var contentOwned = false
        private set
    val mayHandoff get() = active && !contentOwned && !handoffBlocked

    fun begin() { active = true; contentOwned = false; handoffBlocked = false }
    fun recordScroll(consumed: Float) { if (active && consumed != 0f) contentOwned = true }
    fun blockHandoff() { handoffBlocked = true }
    // Keep the decision through the trailing fling; only a new down resets it.
    fun end() { active = false }
}

/** Observe before children, then protect an established scroll after children handle motion.
 * Down/up, taps, reordering and pinch still belong to their normal recognizers. */
@Composable
internal fun Modifier.observeScrollOwnership(ownership: ScrollGestureOwnership,
    onCancel: () -> Unit): Modifier {
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(ownership) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            ownership.begin()
            var released = false
            var multiplePointers = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1 && !multiplePointers) {
                        multiplePointers = true
                        ownership.blockHandoff()
                        cancel()
                    }
                    // Observe a real up before children consume it. Compose's synthetic
                    // cancellation has already-consumed changes; coroutine cancellation
                    // reaches finally without a real up. Neither commits a partial pull.
                    if (event.changes.none { it.pressed }) released = event.changes.any { it.changedToUp() }
                    awaitPointerEvent(PointerEventPass.Main)
                    if (ownership.contentOwned && !multiplePointers) event.changes.filter {
                        it.pressed && it.positionChangedIgnoreConsumed()
                    }.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                ownership.end()
                if (!released) cancel()
            }
        }
    }
}
