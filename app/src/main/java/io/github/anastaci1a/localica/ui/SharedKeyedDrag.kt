package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

/** Shared long-press ownership for mutable songs and queue-selector rows. */
@Composable
internal fun <T : Any, P> Modifier.sharedKeyedDrag(state: KeyedDragState<T, P>, enabled: Boolean,
    reducedMotion: Boolean, ownerKey: Any? = null, acquisitionReady: Boolean = true, itemAt: (Offset) -> T?,
    onHold: (T) -> Unit = {}, keysFor: (T) -> Set<String>? = { setOf(state.keyOf(it)) },
    onCommitted: () -> Unit = {}, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier {
    val currentAcquisitionReady by rememberUpdatedState(acquisitionReady)
    val currentItemAt by rememberUpdatedState(itemAt)
    val currentOnHold by rememberUpdatedState(onHold)
    val currentKeysFor by rememberUpdatedState(keysFor)
    val currentOnCommitted by rememberUpdatedState(onCommitted)
    val currentCommit by rememberUpdatedState(commit)
    return pointerInput(state, ownerKey, reducedMotion, enabled) {
        // Keep this ancestor node installed: removing it cancels every descendant's
        // hit path, including a pinch that temporarily makes the browse query pending.
        // Eligibility/owner changes cancel this transaction; asynchronous presentation
        // readiness gates acquisition only and cannot revoke an owned contact.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled || !currentAcquisitionReady) return@awaitEachGesture
            if (state.drag != null) return@awaitEachGesture
            val item = currentItemAt(down.position) ?: return@awaitEachGesture
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (held != null || !currentAcquisitionReady) return@awaitEachGesture
            currentOnHold(item)
            var moving = false
            var gestureCommit = currentCommit
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1) break
                    if (!change.pressed) {
                        // Compose delivers pointer cancellation as an already-consumed release.
                        val canceled = change.isConsumed
                        change.consume()
                        if (moving) {
                            if (!canceled) state.move(change.position)
                            state.finish(canceled || state.drag?.overCancel == true, reducedMotion, currentOnCommitted, gestureCommit)
                        }
                        break
                    }
                    change.consume()
                    if (!moving && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        val selected = currentKeysFor(item)
                        if (selected != null) {
                            moving = state.begin(item, selected, down.position)
                            // Freeze the adapter's source precondition, not the live insertion target.
                            if (moving) gestureCommit = currentCommit
                        }
                    }
                    if (moving) state.move(change.position)
                }
            } finally { if (moving && state.drag?.dropping == false) state.finish(true, reducedMotion, currentOnCommitted, gestureCommit) }
        }
    }
}
