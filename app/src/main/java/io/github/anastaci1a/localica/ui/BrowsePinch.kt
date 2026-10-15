package io.github.anastaci1a.localica.ui

import androidx.compose.ui.draw.drawWithContent
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

internal data class FrozenBrowseItem(val image: androidx.compose.ui.graphics.ImageBitmap,
    val origin: androidx.compose.ui.unit.IntOffset, val size: androidx.compose.ui.unit.IntSize)

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int, val fraction: Float = 0f, val edge: Int = 0)

@Stable
internal class BrowsePinchMotion {
    val progress = Animatable(1f)
    var held by mutableStateOf(false)
    var fingerProgress = 0f
    var tracking: kotlinx.coroutines.Job? = null
    var capture: kotlinx.coroutines.Job? = null
    var capturing = false
    val frozen = mutableMapOf<Any, FrozenBrowseItem>()
    var source by mutableStateOf<BrowseStyle?>(null)
    var bounds: Map<Any, Rect> = emptyMap()
    var focusKey: Any? = null
    var focusTop: Int = 0
    var focusFraction = 0f
    var focusEdge = 0
    var target: BrowseStyle? = null
    var targetColumns = 1
    var sourceColumns = 1
    val retained = mutableMapOf<Any, androidx.compose.ui.graphics.layer.GraphicsLayer>()
    var anchorIndex = 0
    var anchorTop = 0f
    var rowHeight = 64f
    var viewportWidth = 1f
    var pixelDensity = 1f
    fun sourceContentWidth(key: Any): Int {
        val geometry = BrowseCellGeometry(sourceColumns)
        val cellWidth = bounds[key]?.width?.toInt() ?: geometry.cellWidth(viewportWidth.toInt(), pixelDensity)
        return geometry.contentWidth(cellWidth, pixelDensity)
    }
    fun sourceBounds(key: Any, index: Int): Rect = bounds[key] ?: run {
        val geometry = BrowseCellGeometry(sourceColumns)
        val column = index % sourceColumns
        val left = geometry.cellLeft(viewportWidth.toInt(), pixelDensity, column).toFloat()
        val width = geometry.cellWidth(viewportWidth.toInt(), pixelDensity, column)
        Rect(left,
            anchorTop + ((index / sourceColumns) - (anchorIndex / sourceColumns)) * rowHeight,
            left + width,
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
    val captureLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
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
                    motion.focusKey?.let { onAnchor(BrowseZoomAnchor(route, destination, it, motion.focusTop, motion.focusFraction, motion.focusEdge)) }
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
            motion.pixelDensity = density.density
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
                val layout = gridState.layoutInfo
                val first = layout.visibleItemsInfo.firstOrNull { it.index == 0 }
                val last = layout.visibleItemsInfo.firstOrNull { it.index == layout.totalItemsCount - 1 }
                val tolerance = with(density) { androidx.compose.ui.unit.Dp(1f).toPx() }
                motion.focusEdge = when {
                    !gridState.canScrollBackward || first != null && first.offset.y >= -tolerance -> -1
                    !gridState.canScrollForward || last != null && last.offset.y + last.size.height + layout.afterContentPadding <= layout.viewportEndOffset + tolerance -> 1
                    else -> 0
                }
                motion.focusFraction = ((midpoint.y - nearest.offset.y) / nearest.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                motion.focusKey = nearest.key
                motion.focusTop = (nearest.offset.y + nearest.size.height * motion.focusFraction).toInt()
                onAnchor(BrowseZoomAnchor(route, next, nearest.key, motion.focusTop, motion.focusFraction, motion.focusEdge))
            }
            motion.capturing = true
            motion.capture = scope.launch {
                try {
                    motion.frozen.clear()
                    // Freeze all visible drawings in one readback before destination
                    // reflow mutates nested layers. Pack with gutters so filtering a
                    // retained item never samples its neighbor in the atlas.
                    val drawings = visible.mapNotNull { entry ->
                        motion.retained[entry.key]?.takeUnless { it.isReleased || it.size.width == 0 || it.size.height == 0 }
                            ?.let { entry.key to it }
                    }
                    if (drawings.isNotEmpty()) {
                        val atlasWidth = max(width, drawings.maxOf { it.second.size.width }) + 2
                        var x = 1; var y = 1; var rowHeight = 0
                        val origins = drawings.map { (_, layer) ->
                            if (x + layer.size.width + 1 > atlasWidth) { x = 1; y += rowHeight + 2; rowHeight = 0 }
                            androidx.compose.ui.unit.IntOffset(x, y).also {
                                x += layer.size.width + 2; rowHeight = max(rowHeight, layer.size.height)
                            }
                        }
                        captureLayer.record(density, layoutDirection, androidx.compose.ui.unit.IntSize(atlasWidth, y + rowHeight + 1)) {
                            drawings.forEachIndexed { i, (_, layer) ->
                                translate(origins[i].x.toFloat(), origins[i].y.toFloat()) { drawLayer(layer) }
                            }
                        }
                        val image = captureLayer.toImageBitmap()
                        drawings.forEachIndexed { i, (key, layer) -> motion.frozen[key] = FrozenBrowseItem(image, origins[i], layer.size) }
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
@Composable
internal fun Modifier.retainPinchItem(key: Any, motion: BrowsePinchMotion): Modifier {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    DisposableEffect(motion, key, layer) {
        motion.retained[key] = layer
        onDispose { if (motion.retained[key] === layer) motion.retained.remove(key) }
    }
    return drawWithContent {
        if (motion.source == null && !motion.capturing) {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        } else drawContent()
    }
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
            val geometry = BrowseCellGeometry(motion.targetColumns)
            val column = index % motion.targetColumns
            val width = geometry.cellWidth(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
            val x = geometry.cellLeft(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
            val y = row.offset.y + (index / motion.targetColumns - rowIndex / motion.targetColumns) * targetHeight
            translate(old.left + (x - old.left) * p, old.top + (y - old.top) * p) {
                scale(1f + (width / old.width - 1f) * p, 1f + (targetHeight / old.height - 1f) * p, Offset.Zero) {
                    drawImage(image.image, srcOffset = image.origin, srcSize = image.size, dstSize = image.size)
                }
            }
        }
    }
}
