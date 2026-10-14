package io.github.anastaci1a.localica.ui

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.max
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.anastaci1a.localica.navigation.MediaKind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.BrowseRoute

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int)

@Stable
internal class BrowsePinchMotion {
    val progress = Animatable(1f)
    var held by mutableStateOf(false)
    var fingerProgress = 0f
    var tracking: kotlinx.coroutines.Job? = null
    var capture: kotlinx.coroutines.Job? = null
    var capturing = false
    val frozen = mutableMapOf<Any, androidx.compose.ui.graphics.ImageBitmap>()
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

/** Capture source drawings before reflow, retain them through reversal and save only on release. */
@Composable
internal fun Modifier.browsePinch(enabled: Boolean, motion: BrowsePinchMotion, style: BrowseStyle,
    route: BrowseRoute, gridState: LazyGridState, width: Int, density: Density,
    scope: CoroutineScope, reducedMotion: Boolean, onPreview: (BrowseStyle) -> Unit,
    onCommit: (BrowseStyle) -> Unit, onAnchor: (BrowseZoomAnchor) -> Unit): Modifier {
    return pinchToChangeView(enabled && (motion.source == null || motion.held),
        onProgress = { value ->
            motion.fingerProgress = value
            if (motion.held && !motion.capturing && !reducedMotion) {
                motion.tracking?.cancel()
                motion.tracking = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    motion.progress.animateTo(value, androidx.compose.animation.core.spring(1f, 1600f))
                }
            }
        },
        onRelease = {
            motion.held = false
            motion.tracking?.cancel()
            scope.launch {
                motion.capture?.join()
                val commit = motion.fingerProgress >= .35f
                val destination = if (commit) motion.target else motion.source
                if (reducedMotion) motion.progress.snapTo(if (commit) 1f else 0f)
                else motion.progress.animateTo(if (commit) 1f else 0f, androidx.compose.animation.core.tween(220))
                if (destination != null) {
                    onCommit(destination)
                    motion.focusKey?.let { onAnchor(BrowseZoomAnchor(route, destination, it, motion.focusTop)) }
                }
                // Keep retained drawings through the source-layout handoff.
                withFrameNanos { }; withFrameNanos { }
                motion.source = null
                motion.frozen.clear()
            }
        }) { zoomIn, midpoint ->
        val next = style.zoomStep(zoomIn, with(density) { width.toDp().value },
            albumSongs = route.collection?.kind == MediaKind.Album)
        if (next != style) {
            val visible = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }
            motion.source = style
            motion.target = next
            motion.targetColumns = galleryColumns(next.presentation, with(density) { width.toDp().value }, next.itemsPerRow)
            motion.held = true
            motion.fingerProgress = 0f
            motion.viewportWidth = width.toFloat()
            motion.sourceColumns = galleryColumns(style.presentation, with(density) { width.toDp().value }, style.itemsPerRow)
            motion.bounds = visible.associate { it.key to androidx.compose.ui.geometry.Rect(it.offset.x.toFloat(), it.offset.y.toFloat(),
                (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()) }
            visible.firstOrNull()?.let { first ->
                motion.anchorIndex = first.index - 1; motion.anchorTop = first.offset.y.toFloat()
                motion.rowHeight = first.size.height.toFloat()
            }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { motion.progress.snapTo(if (reducedMotion) 1f else 0f) }
            val nearest = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }.minByOrNull {
                val x = it.offset.x + it.size.width / 2f - midpoint.x
                val y = it.offset.y + it.size.height / 2f - midpoint.y
                x * x + y * y
            }
            if (nearest != null) {
                motion.focusKey = nearest.key; motion.focusTop = nearest.offset.y
                onAnchor(BrowseZoomAnchor(route, next, nearest.key, nearest.offset.y))
            }
            motion.capturing = true
            motion.capture = scope.launch {
                try {
                    motion.frozen.clear()
                    // Flatten before the destination can mutate/release nested
                    // artwork layers. A retained display list alone is not a snapshot.
                    for (entry in visible) {
                        val layer = motion.retained[entry.key]?.takeUnless { it.isReleased } ?: continue
                        motion.frozen[entry.key] = layer.toImageBitmap()
                    }
                    onPreview(next)
                } finally { motion.capturing = false }
                if (motion.held && !reducedMotion) {
                    motion.tracking = scope.launch {
                        motion.progress.animateTo(motion.fingerProgress, androidx.compose.animation.core.spring(1f, 1600f))
                    }
                }
            }
        }
    }
}

/** Keep a bounded set of source drawings alive even when the destination virtualizes them out. */
internal fun Modifier.retainPinchItem(key: Any, motion: BrowsePinchMotion, graphics: GraphicsContext): Modifier = drawWithContent {
    if (motion.source == null && !motion.capturing) {
        val layer = motion.retained.getOrPut(key) {
            if (motion.retained.size >= 96) motion.retained.keys.firstOrNull()?.let { oldest ->
                motion.retained.remove(oldest)?.let(graphics::releaseGraphicsLayer)
            }
            graphics.createGraphicsLayer()
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    } else drawContent()
}

internal fun Modifier.pinchRetainedOverlay(motion: BrowsePinchMotion, grid: LazyGridState, keys: List<String>): Modifier = drawWithContent {
    drawContent()
    if (motion.source == null) return@drawWithContent
    val visible = grid.layoutInfo.visibleItemsInfo
    val liveKeys = visible.map { it.key }.toSet()
    val p = motion.progress.value.coerceIn(0f, 1f)
    val row = visible.firstOrNull { it.key != "browser-notice" } ?: return@drawWithContent
    val rowIndex = keys.indexOf(row.key)
    val targetHeight = max(row.size.height.toFloat(), 1f)
    clipRect {
        motion.bounds.forEach { (key, old) ->
            if (key in liveKeys) return@forEach
            val image = motion.frozen[key] ?: return@forEach
            val index = keys.indexOf(key)
            if (index < 0) return@forEach
            val width = motion.viewportWidth / motion.targetColumns
            val x = index % motion.targetColumns * width
            val y = row.offset.y + (index / motion.targetColumns - rowIndex / motion.targetColumns) * targetHeight
            translate(old.left + (x - old.left) * p, old.top + (y - old.top) * p) {
                scale(1f + (width / old.width - 1f) * p, 1f + (targetHeight / old.height - 1f) * p, Offset.Zero) {
                    drawImage(image)
                }
            }
        }
    }
}
