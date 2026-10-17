package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Observe ownership without consuming clicks, scrolling, or the drag recognizer's stream. */
internal fun Modifier.selectionPanelRelease(session: SessionSelection, owner: Any, enabled: Boolean,
    eligible: (Offset) -> Boolean): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentEligible by rememberUpdatedState(eligible)
    // Availability changes during a query/drag must not end the initiating pointer's hold.
    pointerInput(session, owner) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val token = if (currentEnabled && currentEligible(down.position)) session.beginPresentationHold() else null
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val held = event.changes.any { it.id == down.id && it.pressed }
                } while (held)
            } finally { session.endPresentationHold(token) }
        }
    }
}
