package io.github.anastaci1a.localica.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Keep standard click/long-press/accessibility handling; only arbitrate movement.
 * A scroll winner sees movement before Final cancels the button's activation. */
internal fun Modifier.ownButtonDrag(enabled: Boolean = true, pass: PointerEventPass = PointerEventPass.Initial,
    axis: Int = ControlScrollAxis.None): Modifier = if (!enabled) this else composed {
    val parent = rememberControlScrollContext()
    Modifier.readControlScrollContext(parent).pointerInput(pass, axis) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            val intent = ControlGestureIntent(viewConfiguration.touchSlop, parent.axes, axis)
            do {
                val event = awaitPointerEvent(pass)
                val point = event.changes.firstOrNull { it.id == down.id } ?: break
                if (point.isConsumed) break
                if (event.changes.count { it.pressed } > 1) intent.cancel()
                val movement = point.position - down.position
                when (intent.move(movement.x, movement.y, point.uptimeMillis - down.uptimeMillis)) {
                    ControlGestureOwner.Control -> point.consume()
                    ControlGestureOwner.Scroll, ControlGestureOwner.Cancelled -> {
                        // Leave Initial/Main available to scrolling and pinch recognition.
                        if (point.pressed) awaitPointerEvent(PointerEventPass.Final)
                            .changes.firstOrNull { it.id == down.id }?.consume()
                    }
                    ControlGestureOwner.Pending -> Unit
                }
            } while (point.pressed)
        }
    }
}

@androidx.compose.runtime.Composable
internal fun ActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val defaults = ButtonDefaults.buttonColors()
    val container = controlStateColor(enabled, defaults.containerColor, defaults.disabledContainerColor,
        "Action button container availability")
    val foreground = controlStateColor(enabled, defaults.contentColor, defaults.disabledContentColor,
        "Action button ink availability")
    androidx.compose.material3.Button(controlActivation(enabled, onClick), modifier.ownButtonDrag(enabled), enabled = enabled,
        colors = defaults.copy(containerColor = container, contentColor = foreground,
            disabledContainerColor = container, disabledContentColor = foreground), content = content)
}

@androidx.compose.runtime.Composable
internal fun ActionTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val defaults = ButtonDefaults.textButtonColors()
    val foreground = controlStateColor(enabled, defaults.contentColor, defaults.disabledContentColor,
        "Action text availability")
    androidx.compose.material3.TextButton(controlActivation(enabled, onClick), modifier.ownButtonDrag(enabled), enabled = enabled,
        colors = defaults.copy(contentColor = foreground, disabledContentColor = foreground), content = content)
}

@androidx.compose.runtime.Composable
internal fun ActionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable () -> Unit) {
    val defaults = IconButtonDefaults.iconButtonColors()
    val foreground = controlStateColor(enabled, defaults.contentColor, defaults.disabledContentColor,
        "Action icon availability")
    androidx.compose.material3.IconButton(controlActivation(enabled, onClick), modifier.ownButtonDrag(enabled), enabled = enabled,
        colors = defaults.copy(contentColor = foreground, disabledContentColor = foreground), content = content)
}
