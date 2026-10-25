package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
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

private data class PinchCaptureDrawing(val key: Any, val pass: Int, val layer: GraphicsLayer)

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int, val fraction: Float = 0f, val edge: Int = 0) {
    // LazyGrid places items on integer pixels but retains fractional scroll deltas.
    // Quantize the absolute target once; feeding its residual back each frame dithers.
    fun correction(itemTop: Int, itemHeight: Int): Float =
        (itemTop - (top - itemHeight * fraction).roundToInt()).toFloat()
}

@Stable
internal class BrowsePinchMotion {
    val progress = Animatable(1f)
    var held by mutableStateOf(false)
    var fingerProgress = 0f
    var tracking: kotlinx.coroutines.Job? = null
    var capture: kotlinx.coroutines.Job? = null
    var settling: kotlinx.coroutines.Job? = null
    var disposed = false
    var capturing by mutableStateOf(false)
    var captureProblem = false
    var transaction = 0L
        private set
    var committed = false
    var releaseArtwork: (() -> Unit)? = null
    /** Retire ownership now, but keep decoded source pixels until rollback mounts. */
    fun releaseArtworkAfterHandoff(scope: CoroutineScope) {
        val release = releaseArtwork ?: return
        releaseArtwork = null
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { withFrameNanos { }; withFrameNanos { } }
            finally { release() }
        }
    }
    fun owns(generation: Long) = !disposed && transaction == generation && source != null
    fun failCapture() {
        // The preview has not been published. A held gesture can retire now;
        // a release already joining the readback owns the final cleanup.
        if (held) clearTransaction() else captureProblem = true
    }
    val frozen = mutableMapOf<Any, FrozenBrowseItem>()
    var source by mutableStateOf<BrowseStyle?>(null)
    // Do not replace/release nested artwork layers before the source atlas has
    // finished reading them. Transaction ownership still begins at recognition.
    val visualSource: BrowseStyle? get() = source.takeUnless { capturing }
    var bounds: Map<Any, Rect> = emptyMap()
    var focusKey: Any? = null
    var focusTop: Int = 0
    var focusFraction = 0f
    var focusEdge = 0
    var target: BrowseStyle? = null
    var targetColumns = 1
    var sourceColumns = 1
    val retained = mutableMapOf<Any, androidx.compose.ui.graphics.layer.GraphicsLayer>()
    val currentHighlights = mutableMapOf<Any, androidx.compose.ui.graphics.Color>()
    val itemContent = mutableMapOf<Any, BrowsePinchContent>()
    // Global drawing bypasses the row layer, so entry/reorder alpha must invalidate it too.
    val itemAlpha = mutableStateMapOf<Any, Float>()
    val artwork = mutableMapOf<Any, BrowsePinchLayer>()
    val text = mutableMapOf<Any, BrowsePinchLayer>()
    var viewportCoordinates: LayoutCoordinates? = null
    var anchorIndex = 0
    var anchorTop = 0f
    var rowHeight = 64f
    var viewportWidth = 1f
    var rightToLeft = false
    fun clearTransaction() {
        transaction++
        releaseArtwork?.invoke(); releaseArtwork = null
        committed = false
        held = false; fingerProgress = 0f; capturing = false; captureProblem = false
        source = null; target = null; focusKey = null
        focusTop = 0; focusFraction = 0f; focusEdge = 0
        bounds = emptyMap(); frozen.clear()
        capture = null; tracking = null; settling = null
    }
    fun dispose() {
        disposed = true
        capture?.cancel(); tracking?.cancel(); settling?.cancel()
        clearTransaction()
    }
    var pixelDensity = 1f
    fun highlightSize(key: Any, index: Int, target: androidx.compose.ui.geometry.Size): androidx.compose.ui.geometry.Size {
        if (visualSource == null) return target
        val inset = (BrowseCellGeometry(sourceColumns).cardInset * pixelDensity).roundToInt()
        val bounds = sourceBounds(key, index)
        val sourceWidth = (bounds.width - 2 * inset).coerceAtLeast(0f)
        val sourceHeight = (bounds.height - 2 * inset).coerceAtLeast(0f)
        val p = progress.value.coerceIn(0f, 1f)
        return androidx.compose.ui.geometry.Size(sourceWidth + (target.width - sourceWidth) * p,
            sourceHeight + (target.height - sourceHeight) * p)
    }
    /** Resolve ownership in the layer phase, not when composition installed the modifier.
     * Retirement can invalidate an attached layer before recomposition removes it. */
    fun contentTranslation(key: Any, index: Int, targetPosition: IntOffset, targetWidth: Int,
        targetStyle: BrowseStyle, targetGeometry: BrowseCellGeometry, density: Float): Offset {
        val source = visualSource ?: return Offset.Zero
        val old = sourceBounds(key, index)
        val p = progress.value.coerceIn(0f, 1f)
        val alignment = if (rightToLeft) old.width - targetWidth else 0f
        val gridInset = (targetGeometry.gridInset * density).roundToInt()
        val sourceInset = (BrowseCellGeometry(sourceColumns).cardVerticalInset(source) * density).roundToInt()
        val targetInset = (targetGeometry.cardVerticalInset(targetStyle) * density).roundToInt()
        return Offset((old.left - targetPosition.x - gridInset + alignment) * (1f - p),
            pinchContentTranslation(old.top, targetPosition.y.toFloat(), sourceInset, targetInset, p))
    }
    fun sourceContentWidth(key: Any, index: Int = 0): Int {
        val geometry = BrowseCellGeometry(sourceColumns)
        val cellWidth = bounds[key]?.width?.toInt() ?: geometry.cellWidth(viewportWidth.toInt(), pixelDensity, index % sourceColumns)
        return geometry.contentWidth(cellWidth, pixelDensity)
    }
    fun sourceBounds(key: Any, index: Int): Rect = bounds[key] ?: run {
        val geometry = BrowseCellGeometry(sourceColumns)
        val column = index % sourceColumns
        val width = geometry.cellWidth(viewportWidth.toInt(), pixelDensity, column)
        val leading = geometry.cellLeft(viewportWidth.toInt(), pixelDensity, column).toFloat()
        val left = if (rightToLeft) viewportWidth - leading - width else leading
        Rect(left,
            anchorTop + ((index / sourceColumns) - (anchorIndex / sourceColumns)) * rowHeight,
            left + width,
            anchorTop + ((index / sourceColumns) - (anchorIndex / sourceColumns) + 1) * rowHeight)
    }
}

/** A retired/capturing transaction leaves the live layout at its ordinary placement. */
internal fun Modifier.pinchItemPlacement(motion: BrowsePinchMotion, grid: LazyGridState, key: Any,
    index: Int, style: BrowseStyle, geometry: BrowseCellGeometry): Modifier = graphicsLayer {
    val placed = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    val translation = placed?.let {
        motion.contentTranslation(key, index, it.offset, it.size.width, style, geometry, density)
    } ?: Offset.Zero
    translationX = translation.x
    translationY = translation.y
}

/** The live cell is already laid out with target padding; restore source padding at p=0. */
internal fun pinchContentTranslation(sourceStart: Float, targetStart: Float,
    sourceInset: Int, targetInset: Int, progress: Float): Float =
    (sourceStart - targetStart + sourceInset - targetInset) * (1f - progress.coerceIn(0f, 1f))

/** Linear finger distance; returning to the starting span restores the source. */
internal fun pinchProgress(scale: Float, outward: Boolean): Float =
    ((if (outward) scale - 1f else 1f - scale) / if (outward) .8f else .45f).coerceIn(0f, 1f)

@Composable
internal fun Modifier.pinchToChangeView(enabled: Boolean, gestureKey: Any? = Unit,
    onProgress: ((Float) -> Unit)? = null, onRelease: ((cancelled: Boolean) -> Unit)? = null,
    onStep: (Boolean, Offset) -> Boolean): Modifier {
    val available by rememberUpdatedState(enabled)
    val step by rememberUpdatedState(onStep)
    val progress by rememberUpdatedState(onProgress)
    val release by rememberUpdatedState(onRelease)
    return pointerInput(gestureKey) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Capture callbacks only when accepting the transaction. Its owner
            // remains fixed through preview recomposition and route cancellation.
            var gestureProgress: ((Float) -> Unit)? = null
            var gestureRelease: ((Boolean) -> Unit)? = null
            var owned = false
            var recognized = false
            var accepted = false
            var released = false
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
                            if (!recognized && (scale > threshold || scale < 1f / threshold)) {
                                recognized = true; outward = scale > 1f
                                gestureProgress = progress
                                gestureRelease = release
                                accepted = step(outward, event.calculateCentroid())
                            }
                            if (accepted) {
                                // Consume recognition slop, not a visible fraction of the
                                // layout transition. Return to the original span still cancels.
                                val adjusted = if (outward) 1f + (scale - 1.04f).coerceAtLeast(0f)
                                    else 1f - (1f / 1.04f - scale).coerceAtLeast(0f)
                                gestureProgress?.invoke(pinchProgress(adjusted, outward))
                            }
                        }
                        if (fingers == 0) released = event.changes.none { it.isConsumed }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally { if (accepted) gestureRelease?.invoke(!released) }
        }
    }
}

/** Capture source drawings before reflow, retain them through reversal and save only on release. */
@Composable
internal fun Modifier.browsePinch(enabled: Boolean, motion: BrowsePinchMotion, style: BrowseStyle,
    route: BrowseRoute, gridState: LazyGridState, width: Int, density: Density,
    scope: CoroutineScope, reducedMotion: Boolean, onPreview: (BrowseStyle) -> Unit,
    onCommit: (BrowseStyle) -> Unit, onAnchor: (BrowseZoomAnchor?) -> Unit): Modifier {
    val captureLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val artwork = LocalBrowseArtwork.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    OnModalGestureCancellation {
        val source = motion.source
        if (source != null) {
            val rollback = !motion.committed
            val anchor = motion.focusKey?.let {
                BrowseZoomAnchor(route, source, it, motion.focusTop, motion.focusFraction, motion.focusEdge)
            }
            motion.capture?.cancel(); motion.tracking?.cancel(); motion.settling?.cancel()
            // Retire the generation before callbacks can start another transaction.
            // Cancelled readback/settlement finally blocks must not clear a newer owner.
            if (rollback) motion.releaseArtworkAfterHandoff(scope)
            motion.clearTransaction()
            if (rollback) {
                onPreview(source)
                onAnchor(anchor)
            }
        }
    }
    DisposableEffect(motion) {
        motion.disposed = false
        onDispose { motion.dispose() }
    }
    return pinchToChangeView(enabled && motion.source == null, gestureKey = route.key,
        onProgress = { value ->
            if (motion.held) motion.fingerProgress = value
            if (motion.held && !motion.capturing && !reducedMotion) {
                motion.tracking?.cancel()
                motion.tracking = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    motion.progress.animateTo(value, androidx.compose.animation.core.tween(100, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                }
            }
        },
        onRelease = { cancelled ->
            if (!motion.disposed && motion.source != null && motion.target != null) {
                val generation = motion.transaction
                motion.held = false
                motion.tracking?.cancel()
                motion.settling = scope.launch {
                    try {
                        motion.capture?.join()
                        if (!motion.owns(generation) || motion.captureProblem) return@launch
                        val commit = !cancelled && motion.fingerProgress >= .35f
                        val destination = if (commit) motion.target else motion.source
                        if (reducedMotion) motion.progress.snapTo(if (commit) 1f else 0f)
                        else motion.progress.animateTo(if (commit) 1f else 0f, androidx.compose.animation.core.tween(220))
                        if (destination != null) {
                            if (cancelled) onPreview(destination) else {
                                // A takeover after persistence only retires retained drawings;
                                // it must not undo an already completed, genuine release.
                                motion.committed = true
                                onCommit(destination)
                            }
                            motion.focusKey?.let { onAnchor(BrowseZoomAnchor(route, destination, it, motion.focusTop, motion.focusFraction, motion.focusEdge)) }
                        }
                        // Keep retained drawings through the source-layout handoff.
                        withFrameNanos { }; withFrameNanos { }
                    } finally { if (motion.owns(generation)) motion.clearTransaction() }
                }
            }
        }) { zoomIn, midpoint ->
        val next = style.zoomStep(zoomIn, with(density) { width.toDp().value },
            albumSongs = route.supportsTracklist())
        if (next != style && !motion.disposed && motion.source == null) {
            val visible = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }
            motion.releaseArtwork = artwork?.retainForTransition()
            motion.capturing = true
            motion.source = style
            val generation = motion.transaction
            motion.target = next
            motion.targetColumns = galleryColumns(next.presentation, with(density) { width.toDp().value }, next.itemsPerRow)
            motion.held = true
            motion.fingerProgress = 0f
            motion.viewportWidth = width.toFloat()
            motion.rightToLeft = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            motion.pixelDensity = density.density
            motion.sourceColumns = galleryColumns(style.presentation, with(density) { width.toDp().value }, style.itemsPerRow)
            // LazyGridItemInfo omits the content-padding offset added at placement.
            // Frozen, live and inferred bounds must all use viewport coordinates.
            val sourceInset = (BrowseCellGeometry(motion.sourceColumns).gridInset * density.density).roundToInt()
            motion.bounds = visible.associate { it.key to androidx.compose.ui.geometry.Rect((it.offset.x + sourceInset).toFloat(), it.offset.y.toFloat(),
                (it.offset.x + sourceInset + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()) }
            visible.firstOrNull()?.let { first ->
                motion.anchorIndex = first.index - 1; motion.anchorTop = first.offset.y.toFloat()
                motion.rowHeight = first.size.height.toFloat()
            }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { motion.progress.snapTo(if (reducedMotion) 1f else 0f) }
            val nearest = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }.minByOrNull {
                val x = it.offset.x + sourceInset + it.size.width / 2f - midpoint.x
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
            motion.capture = scope.launch {
                try {
                    motion.frozen.clear()
                    // Freeze all visible drawings in one readback before destination
                    // reflow mutates nested layers. Pack with gutters so filtering a
                    // retained item never samples its neighbor in the atlas.
                    val endpoints = visible.mapNotNull { entry ->
                        motion.itemContent[entry.key]?.let { content ->
                            val index = (entry.index - 1).coerceAtLeast(0)
                            val targetGeometry = BrowseCellGeometry(motion.targetColumns)
                            val targetWidth = targetGeometry.contentWidth(targetGeometry.cellWidth(width, density.density,
                                index % motion.targetColumns), density.density)
                            entry.key to FrozenBrowseContent(content.measure(style, motion.sourceColumns,
                                motion.sourceContentWidth(entry.key, index)),
                                content.measure(next, motion.targetColumns, targetWidth), content.foreground, content.secondary,
                                motion.itemAlpha[entry.key] ?: 1f, content.textOpacity())
                        }
                    }.toMap()
                    val drawings = visible.flatMap { entry ->
                        listOfNotNull(motion.retained[entry.key]?.let { PinchCaptureDrawing(entry.key, 0, it) },
                            motion.text[entry.key]?.layer?.let { PinchCaptureDrawing(entry.key, 1, it) },
                            motion.artwork[entry.key]?.layer?.let { PinchCaptureDrawing(entry.key, 2, it) })
                    }.filter { !it.layer.isReleased && it.layer.size.width > 0 && it.layer.size.height > 0 }
                    if (drawings.isNotEmpty()) {
                        val atlasWidth = max(width, drawings.maxOf { it.layer.size.width }) + 2
                        var x = 1; var y = 1; var rowHeight = 0
                        val origins = drawings.map { drawing ->
                            val layer = drawing.layer
                            if (x + layer.size.width + 1 > atlasWidth) { x = 1; y += rowHeight + 2; rowHeight = 0 }
                            androidx.compose.ui.unit.IntOffset(x, y).also {
                                x += layer.size.width + 2; rowHeight = max(rowHeight, layer.size.height)
                            }
                        }
                        captureLayer.record(density, layoutDirection, androidx.compose.ui.unit.IntSize(atlasWidth, y + rowHeight + 1)) {
                            drawings.forEachIndexed { i, drawing ->
                                translate(origins[i].x.toFloat(), origins[i].y.toFloat()) { drawLayer(drawing.layer) }
                            }
                        }
                        // Keep the tint with the same source snapshot even if playback or
                        // virtualization changes the live rows during readback.
                        val highlights = motion.currentHighlights.toMap()
                        val image = captureLayer.pinchSnapshot(density, layoutDirection)
                        if (!motion.owns(generation)) return@launch
                        fun parts(pass: Int) = drawings.withIndex().filter { it.value.pass == pass }.associate { (i, drawing) ->
                            drawing.key to FrozenBrowsePart(image, origins[i], drawing.layer.size)
                        }
                        val text = parts(1); val art = parts(2)
                        drawings.forEachIndexed { i, drawing -> if (drawing.pass == 0) {
                            motion.frozen[drawing.key] = FrozenBrowseItem(image, origins[i], drawing.layer.size,
                                highlights[drawing.key]?.let(::RetainedCurrentSongHighlight), endpoints[drawing.key], art[drawing.key], text[drawing.key])
                        } }
                    }
                    if (motion.owns(generation)) onPreview(next)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (motion.owns(generation)) { onAnchor(null); motion.failCapture() }
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("BrowsePinch", "Source capture failed; retaining the original view", failure)
                    if (motion.owns(generation)) {
                        onAnchor(null)
                        motion.failCapture()
                    }
                } finally { if (motion.owns(generation)) motion.capturing = false }
                if (motion.owns(generation) && motion.held && !reducedMotion) {
                    motion.tracking = scope.launch {
                        motion.progress.animateTo(motion.fingerProgress, androidx.compose.animation.core.tween(100, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                    }
                }
            }
            true
        } else false
    }
}
