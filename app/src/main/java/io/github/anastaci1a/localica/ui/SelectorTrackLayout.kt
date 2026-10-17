package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.floor

/** Physical host-relative placement, including the fractional remainder exactly once. */
@Composable internal fun Modifier.selectorTrackPlacement(bounds: Rect): Modifier {
    val density = LocalDensity.current
    val x = floor(bounds.left).toInt(); val y = floor(bounds.top).toInt()
    return absoluteOffset { IntOffset(x, y) }.wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
        .requiredSize(with(density) { bounds.width.toDp() },
        with(density) { bounds.height.toDp() }).graphicsLayer {
        translationX = bounds.left - x; translationY = bounds.top - y
    }
}

/** Only visual children move. This recognizer always integrates undeformed host deltas. */
@Composable internal fun Modifier.selectorTrackDrag(horizontal: Boolean, eligible: (Offset) -> Boolean,
    contact: (Boolean) -> Unit, onStart: () -> Unit, onDelta: (Float) -> Unit,
    onEnd: (Boolean) -> Unit): Modifier {
    val accepts by rememberUpdatedState(eligible)
    val touch by rememberUpdatedState(contact)
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val end by rememberUpdatedState(onEnd)
    val parent = rememberControlScrollContext()
    return readControlScrollContext(parent).pointerInput(horizontal) {
        awaitEachGesture {
            // Finish the down event in Main before waiting for slop. Starting in Initial
            // lets the slop waiter see this same down after selectable consumes it,
            // cancelling both the drag and contact feedback immediately.
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!accepts(down.position)) return@awaitEachGesture
            touch(true)
            var started = false
            val intent = ControlGestureIntent(viewConfiguration.touchSlop, parent.axes,
                if (horizontal) ControlScrollAxis.Horizontal else ControlScrollAxis.Vertical)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (point.isConsumed || event.changes.count { it.pressed } > 1) break
                    if (!point.pressed) {
                        if (started) {
                            point.consume(); started = false; end(false)
                        }
                        break
                    }
                    val movement = point.position - down.position
                    when (intent.move(movement.x, movement.y, point.uptimeMillis - down.uptimeMillis)) {
                        ControlGestureOwner.Pending -> Unit
                        ControlGestureOwner.Scroll, ControlGestureOwner.Cancelled -> break
                        ControlGestureOwner.Control -> {
                            val travel = if (horizontal) movement.x else movement.y
                            if (!started) {
                                started = true; start()
                                delta(kotlin.math.sign(travel) *
                                    (kotlin.math.abs(travel) - viewConfiguration.touchSlop).coerceAtLeast(0f))
                            } else {
                                val change = point.positionChange()
                                delta(if (horizontal) change.x else change.y)
                            }
                            point.consume()
                        }
                    }
                }
            } finally {
                if (started) end(true)
                touch(false)
            }
        }
    }
}
