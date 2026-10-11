package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.navigation.BrowseRoute
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int)

@Stable
internal class BrowsePinchMotion {
    val progress = Animatable(1f)
    var held by mutableStateOf(false)
    var source by mutableStateOf<BrowseStyle?>(null)
    var bounds: Map<Any, Rect> = emptyMap()
    var sourceColumns = 1
    var anchorIndex = 0
    var anchorTop = 0f
    var rowHeight = 64f
    var viewportWidth = 1f
    fun sourceBounds(key: Any, index: Int): Rect = bounds[key] ?: run {
        val width = viewportWidth / sourceColumns
        Rect((index % sourceColumns) * width,
            anchorTop + ((index / sourceColumns) - (anchorIndex / sourceColumns)) * rowHeight,
            (index % sourceColumns + 1) * width,
            anchorTop + ((index / sourceColumns) - (anchorIndex / sourceColumns) + 1) * rowHeight)
    }
}

/** Finger progress approaches its destination asymptotically; only release reaches one. */
internal fun pinchProgress(scale: Float, outward: Boolean): Float {
    val distance = (ln(scale) * if (outward) 1f else -1f).coerceAtLeast(0f)
    return (1f - exp(-distance * 4f)).coerceIn(0f, .985f)
}

@Composable
internal fun Modifier.pinchToChangeView(enabled: Boolean, onProgress: ((Float) -> Unit)? = null,
    onRelease: (() -> Unit)? = null, onStep: (Boolean, Offset) -> Unit): Modifier {
    val available by rememberUpdatedState(enabled)
    val step by rememberUpdatedState(onStep)
    val progress by rememberUpdatedState(onProgress)
    val release by rememberUpdatedState(onRelease)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var owned = false
            var committed = false
            var outward = true
            var scale = 1f
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val fingers = event.changes.count { it.pressed }
                    if (!owned && available && fingers >= 2) owned = true
                    if (owned) {
                        if (fingers >= 2) {
                            scale *= event.calculateZoom()
                            val threshold = if (progress != null) 1.04f else 1.18f
                            if (!committed && (scale > threshold || scale < 1f / threshold)) {
                                committed = true; outward = scale > 1f
                                step(outward, event.calculateCentroid())
                            }
                            if (committed) progress?.invoke(pinchProgress(scale, outward))
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally { if (committed) release?.invoke() }
        }
    }
}
