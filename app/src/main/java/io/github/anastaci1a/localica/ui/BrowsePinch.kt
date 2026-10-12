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
    var fingerProgress = 0f
    var tracking: kotlinx.coroutines.Job? = null
    var source by mutableStateOf<BrowseStyle?>(null)
    var bounds: Map<Any, Rect> = emptyMap()
    var focusKey: Any? = null
    var focusTop: Int = 0
    var target: BrowseStyle? = null
    var targetColumns = 1
    var sourceColumns = 1
    val retained = mutableMapOf<Any, androidx.compose.ui.graphics.layer.GraphicsLayer>()
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

/** Linear finger distance; returning to the starting span restores the source. */
internal fun pinchProgress(scale: Float, outward: Boolean): Float =
    ((if (outward) scale - 1f else 1f - scale) / if (outward) .8f else .45f).coerceIn(0f, 1f)

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
                            if (committed) {
                                // Consume recognition slop, not a visible fraction of the
                                // layout transition. Return to the original span still cancels.
                                val adjusted = if (outward) 1f + (scale - 1.04f).coerceAtLeast(0f)
                                    else 1f - (1f / 1.04f - scale).coerceAtLeast(0f)
                                progress?.invoke(pinchProgress(adjusted, outward))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally { if (committed) release?.invoke() }
        }
    }
}
