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
    onStop: suspend (Float) -> Unit): Modifier {
    val delta by rememberUpdatedState(onDelta)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var total = Offset.Zero
            var owned = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!owned && (change.isConsumed || event.changes.count { it.pressed } > 1)) break
                val movement = change.positionChange()
                total += movement
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!owned && abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x) * 1.8f) {
                    owned = true
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { start() }
                    delta(total.y)
                } else if (owned) delta(movement.y)
                if (owned) change.consume()
                else if (abs(total.x) > viewConfiguration.touchSlop) break
            } while (event.changes.any { it.pressed })
            if (owned) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { stop(velocity.calculateVelocity().y) }
        }
    }
}
