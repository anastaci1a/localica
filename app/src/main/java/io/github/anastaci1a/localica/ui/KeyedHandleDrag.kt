package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

/** Keep the pointer owner above removable rows; only a handle can acquire this drag. */
@Composable
internal fun <T : Any, P> Modifier.keyedHandleDrag(state: KeyedDragState<T, P>, enabled: Boolean,
    reducedMotion: Boolean, handleAt: (Offset) -> T?,
    commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier {
    val currentHandleAt by rememberUpdatedState(handleAt)
    val currentCommit by rememberUpdatedState(commit)
    return pointerInput(state, enabled, reducedMotion) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            if (!enabled || state.drag != null) return@awaitEachGesture
            val held = currentHandleAt(down.position) ?: return@awaitEachGesture
            down.consume()
            var moving = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1) break
                    if (!change.pressed) {
                        val canceled = change.isConsumed
                        change.consume()
                        if (moving) {
                            if (!canceled) state.move(change.position)
                            state.finish(canceled || state.drag?.overCancel == true, reducedMotion, commit = currentCommit)
                        }
                        break
                    }
                    change.consume()
                    if (!moving && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moving = state.begin(held, setOf(state.keyOf(held)), down.position)
                    }
                    if (moving) state.move(change.position)
                }
            } finally {
                if (moving && state.drag?.dropping == false) state.finish(true, reducedMotion, commit = currentCommit)
            }
        }
    }
}
