package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Resolve direction after children; once claimed, one finger owns one surface until up. */
@Composable
internal fun Modifier.playerVerticalDrag(onDelta: (Float) -> Unit, onStart: suspend () -> Unit,
    onStop: suspend (Float) -> Unit, onCancel: suspend () -> Unit, canStart: () -> Boolean = { true },
    onCatch: () -> Boolean = { false }): Modifier {
    val delta by rememberUpdatedState(onDelta)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val allowed by rememberUpdatedState(canStart)
    val catch by rememberUpdatedState(onCatch)
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        awaitEachGesture {
            // Touch-down on a moving surface catches it before children see the touch (#608 contact): a still
            // release settles by position from rest; movement past slop drags from the caught state.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!allowed()) return@awaitEachGesture
            val caught = catch()
            if (caught) down.consume() else awaitPointerEvent(PointerEventPass.Main)
            val pass = if (caught) PointerEventPass.Initial else PointerEventPass.Main
            var total = Offset.Zero
            var owned = caught
            var dragging = false
            var released = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, Offset.Zero)
            try { do {
                val event = awaitPointerEvent(pass)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // The release sample lets a still hold report zero velocity (no move events arrive while still).
                if (!change.pressed) { velocity.addPosition(change.uptimeMillis, total + change.positionChange())
                    released = caught || !change.isConsumed; if (caught) change.consume(); break }
                if (event.changes.count { it.pressed } > 1 || !owned && change.isConsumed) break
                val movement = change.positionChange()
                total += movement
                // The surface moves under the pointer; integrate deltas in a stable frame.
                velocity.addPosition(change.uptimeMillis, total)
                if (!dragging && abs(total.y) > viewConfiguration.touchSlop && (caught || abs(total.y) > abs(total.x) * 1.3f)) {
                    owned = true; dragging = true
                    if (!caught) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { start() }
                    delta(total.y)
                } else if (dragging) delta(movement.y)
                if (owned) change.consume()
                else if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y) * 1.3f) break
            } while (event.changes.any { it.pressed }) }
            finally {
                if (owned) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    if (released || caught) stop(if (dragging) velocity.calculateVelocity().y else 0f) else cancel()
                }
            }
        }
    }
}

/** Finger directions the player surface moves: up opens Queue or expands, down collapses.
 * An owned scrub keeps every direction for the seek bar. */
internal fun playerDragDirections(expanded: Boolean, scrubbing: Boolean): Int = when {
    scrubbing -> ControlScrollDirection.None
    expanded -> ControlScrollDirection.Vertical
    else -> ControlScrollDirection.Up
}
