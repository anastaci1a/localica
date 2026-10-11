package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.navigation.BrowseRoute

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int)

/** Claim two-finger input before children, then retain ownership until every finger lifts. */
@Composable
internal fun Modifier.pinchToChangeView(enabled: Boolean, onStep: (Boolean, Offset) -> Unit): Modifier {
    val available by rememberUpdatedState(enabled)
    val step by rememberUpdatedState(onStep)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var owned = false
            var committed = false
            var scale = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (!owned && available && fingers >= 2) owned = true
                if (owned) {
                    if (!committed && fingers >= 2) {
                        scale *= event.calculateZoom()
                        if (scale > 1.18f || scale < 1f / 1.18f) {
                            committed = true
                            step(scale > 1f, event.calculateCentroid())
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
