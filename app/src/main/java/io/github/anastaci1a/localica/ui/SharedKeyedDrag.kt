package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

/** Shared long-press ownership for every list drag owner (mutable songs, Queue, multi-select stacks,
 * queue selector). The gesture owns the drag haptics (#736): [pickupHaptic] gives the Strong pickup
 * for rows without their own long-press owner (song rows pulse through their long-click), every
 * finger lift after a drag is Strong, and a system cancel is silent. A recognized hold publishes
 * [KeyedDragState.held], so every owner lifts the held item in place before the drag (#735). */
@Composable
internal fun <T : Any, P> Modifier.sharedKeyedDrag(state: KeyedDragState<T, P>, enabled: Boolean,
    reducedMotion: Boolean, ownerKey: Any? = null, acquisitionReady: Boolean = true, pickupHaptic: Boolean = false,
    itemAt: (Offset) -> T?,
    onHold: (T) -> Unit = {}, keysFor: (T) -> Set<String>? = { setOf(state.keyOf(it)) },
    onCommitted: () -> Unit = {}, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier {
    val currentAcquisitionReady by rememberUpdatedState(acquisitionReady)
    val currentItemAt by rememberUpdatedState(itemAt)
    val currentOnHold by rememberUpdatedState(onHold)
    val currentKeysFor by rememberUpdatedState(keysFor)
    val currentOnCommitted by rememberUpdatedState(onCommitted)
    val currentCommit by rememberUpdatedState(commit)
    val haptics by rememberUpdatedState(rememberControlHaptics())
    val currentPickupHaptic by rememberUpdatedState(pickupHaptic)
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
            val heldKey = state.keyOf(item)
            state.held = heldKey
            if (currentPickupHaptic) haptics.emit(ControlHaptic.Strong)
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
                            if (!canceled) { state.move(change.position); haptics.dragReleased() }
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
            } finally {
                if (state.held == heldKey) state.held = null
                if (moving && state.drag?.dropping == false) state.finish(true, reducedMotion, currentOnCommitted, gestureCommit)
            }
        }
    }
}
