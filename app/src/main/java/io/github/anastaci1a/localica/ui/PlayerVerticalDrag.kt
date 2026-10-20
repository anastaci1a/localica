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
    onStop: suspend (Float) -> Unit, onCancel: suspend () -> Unit, canStart: () -> Boolean = { true }): Modifier {
    val delta by rememberUpdatedState(onDelta)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val allowed by rememberUpdatedState(canStart)
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!allowed()) return@awaitEachGesture
            var total = Offset.Zero
            var owned = false
            var released = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, Offset.Zero)
            try { do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) { released = !change.isConsumed; break }
                if (!owned && (change.isConsumed || event.changes.count { it.pressed } > 1)) break
                val movement = change.positionChange()
                total += movement
                // The surface moves under the pointer; integrate deltas in a stable frame.
                velocity.addPosition(change.uptimeMillis, total)
                if (!owned && abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x) * 1.3f) {
                    owned = true
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { start() }
                    delta(total.y)
                } else if (owned) delta(movement.y)
                if (owned) change.consume()
                else if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y) * 1.3f) break
            } while (event.changes.any { it.pressed }) }
            finally {
                if (owned) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    if (released) stop(velocity.calculateVelocity().y) else cancel()
                }
            }
        }
    }
}
