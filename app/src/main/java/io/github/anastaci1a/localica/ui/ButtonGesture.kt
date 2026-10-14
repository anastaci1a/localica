package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** A button owns a contact through release, even after the finger leaves its bounds.
 * Leave down/up to clickable (including long press and accessibility); consuming motion
 * cancels activation after dragging and prevents a containing scroller stealing the contact.
 * Content selections/carousels and controls with their own drag recognizer do not use this. */
internal fun Modifier.ownButtonDrag(enabled: Boolean = true, pass: PointerEventPass = PointerEventPass.Initial): Modifier = if (!enabled) this else pointerInput(pass) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        var dragged = false
        do {
            val event = awaitPointerEvent(pass)
            val point = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((point.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
            if (dragged) point.consume()
        } while (event.changes.any { it.id == down.id && it.pressed })
    }
}

@androidx.compose.runtime.Composable
internal fun ActionTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.material3.TextButton(onClick, modifier.ownButtonDrag(enabled), enabled = enabled, content = content)
}

@androidx.compose.runtime.Composable
internal fun ActionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.material3.IconButton(onClick, modifier.ownButtonDrag(enabled), enabled = enabled, content = content)
}
