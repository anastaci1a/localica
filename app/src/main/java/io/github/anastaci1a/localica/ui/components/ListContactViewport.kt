package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** The list viewport's existing passive contact lifetime. Begin runs before list drag/slop;
 * end also runs on cancellation/disposal. The observer consumes no event or scrolling delta. */
@Composable internal fun ListContactViewport(modifier: Modifier, onBegin: () -> Unit,
    onEnd: () -> Unit, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val begin = rememberUpdatedState(onBegin)
    val end = rememberUpdatedState(onEnd)
    BoxWithConstraints(modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            try {
                begin.value()
                do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                while (event.changes.any { it.pressed })
            } finally { end.value() }
        }
    }, content = content)
}
