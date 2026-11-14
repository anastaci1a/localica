package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.components.BindBrowsePinchProgress
import io.github.anastaci1a.localica.ui.components.BrowsePinchProgressOwner
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

private data class PinchCaptureDrawing(val key: Any, val pass: Int, val layer: GraphicsLayer,
    val size: androidx.compose.ui.unit.IntSize = layer.size)
private object PinchHeaderCaptureKey
private object PinchHeaderStaticCaptureKey

/** Input ownership outlives a release when its adjacent view is waiting for an earlier handoff.
 * A [view] request (the View popup, #627) names its destination and arrives already released. */
internal class BrowsePinchGesture(val zoomIn: Boolean, val midpoint: Offset,
    var source: BrowseStyle, var destination: BrowseStyle, val epoch: Long,
    val view: BrowseStyle? = null, val commit: ((BrowseStyle) -> Unit)? = null) {
    val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
    var pending = false
    var released = false
    var cancelled = false
    var travel = 0f
    var generation = -1L
    var retargeted = false
    var origin = 0f
    var direction = 1f
    var fallbackTarget = false
    fun progress() = if (retargeted) (origin + direction * travel).coerceIn(0f, 1f) else travel
    fun targetOnRelease() = if (retargeted) {
        if (cancelled || travel < .35f) fallbackTarget else direction > 0f
    } else !cancelled && travel >= .35f
}

internal data class BrowseZoomAnchor(val route: BrowseRoute, val style: BrowseStyle, val key: Any, val top: Int, val fraction: Float = 0f, val edge: Int = 0) {
    // LazyGrid places items on integer pixels but retains fractional scroll deltas.
    // Quantize the absolute target once; feeding its residual back each frame dithers.
    fun correction(itemTop: Int, itemHeight: Int): Float =
        (itemTop - (top - itemHeight * fraction).roundToInt()).toFloat()
}

@Stable
internal class BrowsePinchMotion {
    val beyondBounds = BrowseBeyondBounds()
    internal val progressOwner = BrowsePinchProgressOwner()
    var progress by mutableStateOf(progressOwner.create(1f))
        private set
    /** Retired layout keeps its terminal clock while the next transaction starts at zero. */
    fun beginGeometry() {
        tracking?.cancel(); tracking = null
        caughtUp = false
        progress = progressOwner.create(0f)
    }
    var held by mutableStateOf(false)
    var fingerProgress = 0f
    var tracking: kotlinx.coroutines.Job? = null
    private var caughtUp = false
    /** Once the destination is ready, ease from the shown progress to the fingers over
     * [PinchCatchUpMillis] (reading them every frame), then follow them directly. Restarting
     * a tween on every event kept the view a tween behind the fingers (#520). */
    fun followFingers(scope: CoroutineScope) {
        if (!held || capturing || !destinationReady) return
        val clock = progress
        clock.follow(fingerProgress)
        if (caughtUp) {
            tracking?.cancel()
            // Publish pointer-driven reversals before the frame begins. A snap animation's
            // frame callback can run after layout has already consumed the preceding value.
            tracking = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                clock.snapTo(fingerProgress)
            }
            return
        }
        if (tracking?.isActive == true) return
        tracking = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            clock.catchUp { if (progress === clock) caughtUp = true }
        }
    }
    var capture: kotlinx.coroutines.Job? = null
    var settling: kotlinx.coroutines.Job? = null
    var settleOwner = 0L
    var releaseTarget = false
    var gesture by mutableStateOf<BrowsePinchGesture?>(null)
    var gestureEpoch = 0L
        private set
    fun cancelGestures() { gestureEpoch++; gesture = null }
    fun resumeFingers() {
        tracking?.cancel(); tracking = null
        caughtUp = false
    }
    var disposed = false
    var capturing by mutableStateOf(false)
    var readbackPending by mutableStateOf(false)
    val recordingFrozen: Boolean get() = capturing || readbackPending
    var destinationReady by mutableStateOf(false)
    var header: CollectionPinchHeader? = null
    /** Set when the scrolled-away header cannot be measured: this transaction then runs without it. */
    var headerless = false
    val activeHeader: CollectionPinchHeader? get() = if (headerless) null else header
    var captureProblem = false
    var transaction = 0L
        private set
    var committed = false
    var takeover = false
    var releaseArtwork: (() -> Unit)? = null
    private val releaseAfterRead = mutableListOf<() -> Unit>()
    fun finishReadback() {
        readbackPending = false
        val releases = releaseAfterRead.toList()
        releaseAfterRead.clear()
        var failure: Throwable? = null
        releases.forEach { release -> try { release() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure!!.addSuppressed(caught)
        } }
        failure?.let { throw it }
    }
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
    // Result index of each captured key, so no whole-result key map exists; one instance, read at draw time (#957).
    val indices = HashMap<String, Int>()
    var focusKey: Any? = null
    var focusTop: Int = 0
    var focusFraction = 0f
    var focusEdge = 0
    var target: BrowseStyle? = null
    var targetColumns = 1
    var sourceColumns = 1
    val retained = mutableMapOf<Any, androidx.compose.ui.graphics.layer.GraphicsLayer>()
    var hoverLift: (Any) -> HoverGeometry? = { null }
    /** Enters an explicit view change into this same transaction as an already released pinch.
     * Returns its completion, or null when no transaction could start (#627). */
    var viewChange: ((BrowseStyle, (BrowseStyle) -> Unit) -> kotlinx.coroutines.Deferred<Unit>?)? = null
    val currentHighlights = mutableMapOf<Any, androidx.compose.ui.graphics.Color>()
    val itemContent = mutableMapOf<Any, BrowsePinchContent>()
    // Global drawing bypasses the row layer, so entry/reorder alpha must invalidate it too.
    val itemAlpha = mutableStateMapOf<Any, Float>()
    val artwork = mutableMapOf<Any, BrowsePinchLayer>()
    val text = mutableMapOf<Any, BrowsePinchLayer>()
    var viewportCoordinates: LayoutCoordinates? = null
    var invalidateViewport: (() -> Unit)? = null
    var anchorIndex = 0
    var anchorTop = 0f
    var rowHeight = 64f
    var viewportWidth = 1f
    var rightToLeft = false
    fun clearTransaction() {
        if (readbackPending) {
            releaseArtwork?.let { releaseAfterRead.add(it) }; releaseArtwork = null
            header?.detachPin()?.let { releaseAfterRead.add(it) }
        }
        header?.clear()
        headerless = false
        transaction++
        releaseArtwork?.invoke(); releaseArtwork = null
        committed = false
        takeover = false
        held = false; fingerProgress = 0f; caughtUp = false; capturing = false; captureProblem = false
        destinationReady = false
        source = null; target = null; focusKey = null
        focusTop = 0; focusFraction = 0f; focusEdge = 0
        bounds = emptyMap(); indices.clear(); frozen.clear()
        tracking?.cancel()
        capture = null; tracking = null; settling = null
    }
    /** Compact phase line for the diagnostics trail (#535 b); reads state only. */
    fun breadcrumb(): String {
        val request = gesture
        return "pinch tx=$transaction ${source?.let { "${it.presentation}/${it.listSize}" }}>${target?.let { "${it.presentation}/${it.listSize}" }} " +
            "held=$held cap=$capturing rb=$readbackPending dest=$destinationReady commit=$committed " +
            "g=${request?.let { "p${it.pending}/r${it.released}/c${it.cancelled}" }}"
    }
    /** Stuck-state report (#535 b); reads state only. */
    fun diagnostics(): String {
        fun BrowseStyle?.name() = this?.let { "${it.presentation}/${it.listSize}/${it.itemsPerRow}" }
        val request = gesture
        return "transaction=$transaction source=${source.name()} target=${target.name()} progress=${progress.value} " +
            "held=$held capturing=$capturing readbackPending=$readbackPending destinationReady=$destinationReady " +
            "committed=$committed takeover=$takeover releaseTarget=$releaseTarget headerless=$headerless " +
            "capture=${capture?.isActive} settling=${settling?.isActive} focus=$focusKey@$focusTop edge=$focusEdge " +
            "gesture=${request?.let { "pending=${it.pending} released=${it.released} cancelled=${it.cancelled} " +
                "retargeted=${it.retargeted} travel=${it.travel} generation=${it.generation} finished=${it.finished.isCompleted}" }} " +
            "header=[${activeHeader?.diagnostics()}]"
    }
    fun dispose() {
        disposed = true
        cancelGestures()
        capture?.cancel(); tracking?.cancel(); settling?.cancel()
        clearTransaction()
    }
    /** The departure and arrival fades of [transaction]'s current destination layout (#559). */
    var fadePlan: PinchFadePlan? = null
    var pixelDensity = 1f
    fun resolveHover(key: Any?, current: HoverGeometry, geometryProgress: Float = progress.value): HoverGeometry {
        if (key == null || visualSource == null) return current
        val captured = frozen[key]?.content?.hover ?: return current
        return interpolateHoverGeometry(captured, current, geometryProgress)
    }
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.pinchItemPlacement(motion: BrowsePinchMotion, grid: LazyGridState, key: Any,
    index: Int, style: BrowseStyle, geometry: BrowseCellGeometry): Modifier =
    graphicsLayer {
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
internal const val PinchCatchUpMillis = 100f

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
                                // The full finger travel, including the direction threshold: the
                                // motion's catch-up ease covers it, so there is no dead zone (#520).
                                // Returning to the original span still cancels.
                                gestureProgress?.invoke(pinchProgress(scale, outward))
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
    BindBrowsePinchProgress(motion.progressOwner)
    val graphicsContext = androidx.compose.ui.platform.LocalGraphicsContext.current
    val readback = remember { PinchReadback() }
    val artwork = LocalBrowseArtwork.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val currentStyle by rememberUpdatedState(style)
    suspend fun restoreHeaderSource(generation: Long, source: BrowseStyle) {
        val header = motion.activeHeader ?: return
        if (!motion.owns(generation)) return
        motion.progress.snapTo(0f)
        header.beginHandoff(false)
        onPreview(source)
        motion.focusKey?.let { key ->
            onAnchor(BrowseZoomAnchor(route, source, key, checkNotNull(motion.bounds[key]).top.toInt(), 0f, motion.focusEdge))
        }
        header.awaitHandoff()
    }
    OnModalGestureCancellation {
        if (motion.source != null || motion.gesture != null)
            io.github.anastaci1a.localica.diagnostics.Diagnostics.info(PinchDiagnosticsTag, "modal-cancellation ${motion.diagnostics()}")
        motion.cancelGestures()
        val source = motion.source
        if (source != null) {
            val header = motion.activeHeader
            if (header != null && !motion.capturing) {
                if (!motion.committed && !motion.takeover) {
                    val generation = motion.transaction
                    motion.takeover = true; motion.held = false
                    val capturing = motion.capture
                    val settling = motion.settling
                    capturing?.cancel(); settling?.cancel(); motion.tracking?.cancel()
                    motion.settling = scope.launch {
                        try {
                            capturing?.join(); settling?.join()
                            if (!motion.owns(generation)) return@launch
                            motion.progress.snapTo(0f)
                            header.beginHandoff(false)
                            onPreview(source)
                            motion.focusKey?.let { key ->
                                val top = checkNotNull(motion.bounds[key]).top.toInt()
                                onAnchor(BrowseZoomAnchor(route, source, key, top, 0f, motion.focusEdge))
                            }
                            header.awaitHandoff()
                        } finally { if (motion.owns(generation)) motion.clearTransaction() }
                    }
                }
                // A committed release still owns its verified destination handoff.
                return@OnModalGestureCancellation
            }
            val rollback = !motion.committed
            val anchor = motion.focusKey?.let {
                BrowseZoomAnchor(route, source, it, motion.focusTop, motion.focusFraction, motion.focusEdge)
            }
            motion.capture?.cancel(); motion.tracking?.cancel(); motion.settling?.cancel()
            // Retire the generation before callbacks can start another transaction.
            // Cancelled readback/settlement finally blocks must not clear a newer owner.
            if (rollback && !motion.readbackPending) motion.releaseArtworkAfterHandoff(scope)
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
    fun settle(request: BrowsePinchGesture) {
        val generation = request.generation
        if (!motion.owns(generation) || motion.target == null) {
            request.finished.complete(Unit)
            if (motion.gesture === request) motion.gesture = null
            return
        }
        val owner = ++motion.settleOwner
        val commit = request.targetOnRelease()
        motion.releaseTarget = commit
        request.destination = requireNotNull(if (commit) motion.target else motion.source)
        motion.held = false
        motion.tracking?.cancel()
        motion.settling = scope.launch {
            try {
                motion.capture?.join()
                if (!motion.owns(generation) || motion.captureProblem) return@launch
                val destination = if (commit) motion.target else motion.source
                if (reducedMotion) motion.progress.snapTo(if (commit) 1f else 0f)
                else motion.progress.animateTo(if (commit) 1f else 0f)
                if (destination != null) {
                    motion.activeHeader?.beginHandoff(commit)
                    // A cancelled retarget still owes the preceding genuine release its endpoint.
                    if (request.cancelled && !request.retargeted) onPreview(destination) else {
                        motion.committed = true
                        (request.commit ?: onCommit)(destination)
                    }
                    motion.focusKey?.let {
                        val sourceTop = motion.bounds[it]?.top
                        onAnchor(if (!commit && motion.activeHeader != null && sourceTop != null)
                            BrowseZoomAnchor(route, destination, it, sourceTop.toInt(), 0f, motion.focusEdge)
                        else BrowseZoomAnchor(route, destination, it, motion.focusTop, motion.focusFraction, motion.focusEdge))
                    }
                }
                if (motion.activeHeader != null) motion.activeHeader?.awaitHandoff()
                else { withFrameNanos { }; withFrameNanos { } }
            } finally {
                // Retargeting keeps this transaction's frozen endpoints but replaces its settle.
                if (motion.owns(generation) && motion.settleOwner == owner && !motion.takeover)
                    motion.clearTransaction()
                if (motion.gesture === request) motion.gesture = null
                request.finished.complete(Unit)
            }
        }
    }
    fun startStep(style: BrowseStyle, request: BrowsePinchGesture): Boolean {
        val zoomIn = request.zoomIn
        val midpoint = request.midpoint
        val next = request.view ?: style.zoomStep(zoomIn, with(density) { width.toDp().value },
            albumSongs = route.supportsTracklist())
        return if (next != style && !motion.disposed && motion.source == null && !motion.readbackPending) {
            val visible = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }
            if (motion.activeHeader != null && visible.isEmpty()) return false
            motion.releaseArtwork = artwork?.retainForTransition()
            motion.capturing = true
            motion.source = style
            val generation = motion.transaction
            request.generation = generation
            request.source = style; request.destination = next; request.pending = false
            motion.target = next
            motion.targetColumns = galleryColumns(next.presentation, with(density) { width.toDp().value }, next.itemsPerRow)
            motion.held = !request.released
            motion.fingerProgress = request.progress()
            motion.destinationReady = false
            motion.viewportWidth = width.toFloat()
            motion.rightToLeft = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            motion.pixelDensity = density.density
            motion.sourceColumns = galleryColumns(style.presentation, with(density) { width.toDp().value }, style.itemsPerRow)
            // LazyGridItemInfo omits the content-padding offset added at placement.
            // Frozen, live and inferred bounds must all use viewport coordinates.
            val sourceInset = (BrowseCellGeometry(motion.sourceColumns).gridInset * density.density).roundToInt()
            motion.bounds = visible.associate { it.key to androidx.compose.ui.geometry.Rect((it.offset.x + sourceInset).toFloat(), it.offset.y.toFloat(),
                (it.offset.x + sourceInset + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()) }
            motion.indices.clear(); visible.associateTo(motion.indices) { it.key.toString() to it.index - 1 }
            visible.firstOrNull()?.let { first ->
                motion.anchorIndex = first.index - 1; motion.anchorTop = first.offset.y.toFloat()
                motion.rowHeight = first.size.height.toFloat()
            }
            motion.beginGeometry()
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
            }
            motion.capture = scope.launch {
                var previewPublished = false
                try {
                    motion.frozen.clear()
                    motion.activeHeader?.let { header ->
                        header.viewport = motion.viewportCoordinates
                        // A header scrolled far away cannot be placed by the public beyond-bounds
                        // measurement. It has nothing visible to freeze: pinch without it.
                        val measured = try { header.begin(generation, style, next); true } catch (failure: IllegalStateException) {
                            if (gridState.layoutInfo.visibleItemsInfo.any { it.key == "browser-notice" }) throw failure
                            android.util.Log.w("BrowsePinch", "Scrolled-away header not measurable; pinching without it", failure)
                            motion.headerless = true
                            false
                        }
                        if (measured) {
                        // No native read has begun: this preparation wait can still
                        // cancel normally. Pinning keeps the offscreen producer mounted.
                        kotlinx.coroutines.withTimeout(3000) {
                            while (header.recordedRequest != header.captureRequest || header.layer?.size == androidx.compose.ui.unit.IntSize.Zero || header.staticLayer?.size == androidx.compose.ui.unit.IntSize.Zero) {
                                withFrameNanos { }
                            }
                        }
                        }
                    }
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
                                motion.itemAlpha[entry.key] ?: 1f, content.textOpacity(), motion.hoverLift(entry.key))
                        }
                    }.toMap()
                    val drawings = (visible.flatMap { entry ->
                        listOfNotNull(motion.retained[entry.key]?.let { PinchCaptureDrawing(entry.key, 0, it) },
                            motion.text[entry.key]?.layer?.let { PinchCaptureDrawing(entry.key, 1, it) },
                            motion.artwork[entry.key]?.layer?.let { PinchCaptureDrawing(entry.key, 2, it) })
                    } + listOfNotNull(motion.activeHeader?.layer?.let { PinchCaptureDrawing(PinchHeaderCaptureKey, 3, it) },
                        motion.activeHeader?.staticLayer?.let { PinchCaptureDrawing(PinchHeaderStaticCaptureKey, 4, it) }))
                        .filter { !it.layer.isReleased && it.size.width > 0 && it.size.height > 0 }
                    if (drawings.isNotEmpty()) {
                        val atlasLayout = packPinchAtlas(drawings.map { it.size }, width)
                        val origins = atlasLayout.origins
                        if (motion.activeHeader?.observer != null) android.util.Log.i("PinchAtlas",
                            "width=${atlasLayout.size.width} height=${atlasLayout.size.height} regions=${drawings.size}")
                        // Keep the tint with the same source snapshot even if playback or
                        // virtualization changes the live rows during readback.
                        val highlights = motion.currentHighlights.toMap()
                        motion.readbackPending = true
                        val image = try {
                            readback.capture(graphicsContext, density, layoutDirection, onPhase = { phase ->
                                if (motion.activeHeader?.observer != null) android.util.Log.i("PinchAtlas", "phase=$phase generation=$generation")
                            }, onDeadline = {
                                if (motion.owns(generation)) { onAnchor(null); motion.clearTransaction() }
                            }) { atlas ->
                                atlas.record(density, layoutDirection, atlasLayout.size) {
                                    drawings.forEachIndexed { i, drawing ->
                                        translate(origins[i].x.toFloat(), origins[i].y.toFloat()) { drawLayer(drawing.layer) }
                                    }
                                }
                            }
                        } finally { motion.finishReadback() }
                        if (!motion.owns(generation)) return@launch
                        fun parts(pass: Int) = drawings.withIndex().filter { it.value.pass == pass }.associate { (i, drawing) ->
                            drawing.key to FrozenBrowsePart(image, origins[i], drawing.size)
                        }
                        val text = parts(1); val art = parts(2)
                        drawings.forEachIndexed { i, drawing -> if (drawing.pass == 0) {
                            motion.frozen[drawing.key] = FrozenBrowseItem(image, origins[i], drawing.size,
                                highlights[drawing.key]?.let(::RetainedCurrentSongHighlight), endpoints[drawing.key], art[drawing.key], text[drawing.key])
                        } }
                        motion.activeHeader?.freeze(checkNotNull(parts(3)[PinchHeaderCaptureKey]),
                            checkNotNull(parts(4)[PinchHeaderStaticCaptureKey]))
                    }
                    if (motion.owns(generation)) {
                        motion.capturing = false
                        onPreview(next)
                        previewPublished = true
                        motion.focusKey?.let { onAnchor(BrowseZoomAnchor(route, next, it, motion.focusTop, motion.focusFraction, motion.focusEdge)) }
                        motion.activeHeader?.let { header ->
                            check(kotlinx.coroutines.withTimeoutOrNull(3000) { header.awaitDestination(); true } == true) {
                                "Header target did not supply anchor-corrected geometry before preview expiry"
                            }
                        }
                        motion.destinationReady = true
                        if (reducedMotion) motion.progress.snapTo(1f)
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    android.util.Log.w("BrowsePinch", "Header source drawing was not acknowledged before native submission", timeout)
                    if (motion.owns(generation)) { onAnchor(null); motion.failCapture() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (motion.owns(generation)) { onAnchor(null); motion.failCapture() }
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("BrowsePinch", "Source capture failed; retaining the original view", failure)
                    if (motion.owns(generation)) {
                        if (previewPublished && motion.activeHeader != null && !motion.takeover) {
                            motion.held = false
                            restoreHeaderSource(generation, style)
                            if (motion.owns(generation)) motion.clearTransaction()
                        } else { onAnchor(null); motion.failCapture() }
                    }
                } finally { if (motion.owns(generation)) motion.capturing = false }
                if (motion.owns(generation) && !reducedMotion) motion.followFingers(scope)
            }
            if (request.released) settle(request)
            true
        } else false
    }
    fun sameView(a: BrowseStyle?, b: BrowseStyle): Boolean {
        if (a == null) return false
        if (a.presentation == Presentation.List || b.presentation == Presentation.List)
            return a.presentation == b.presentation && a.listSize == b.listSize
        val widthDp = with(density) { width.toDp().value }
        return galleryColumns(a.presentation, widthDp, a.itemsPerRow) == galleryColumns(b.presentation, widthDp, b.itemsPerRow)
    }
    val admitted by rememberUpdatedState(enabled)
    SideEffect {
        motion.viewChange = change@{ view, commit ->
            val source = currentStyle
            if (!admitted || motion.disposed || motion.takeover || motion.gesture != null || motion.source != null ||
                motion.readbackPending || sameView(source, view)) return@change null
            // No fingers: anchor the topmost fully visible item, as a pinch at its top edge would.
            val visible = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }
            val anchor = visible.firstOrNull { it.offset.y >= 0 } ?: visible.firstOrNull() ?: return@change null
            val columns = galleryColumns(source.presentation, with(density) { width.toDp().value }, source.itemsPerRow)
            val inset = (BrowseCellGeometry(columns).gridInset * density.density).roundToInt()
            val midpoint = Offset(anchor.offset.x + inset + anchor.size.width / 2f, anchor.offset.y + 1f)
            val request = BrowsePinchGesture(false, midpoint, source, view, motion.gestureEpoch, view, commit)
            request.released = true; request.travel = 1f
            motion.gesture = request
            if (startStep(source, request)) request.finished
            else { motion.gesture = null; request.finished.complete(Unit); null }
        }
    }
    return pinchToChangeView(enabled && !motion.takeover && motion.gesture?.cancelled != true &&
        (motion.source == null && !motion.readbackPending ||
            !motion.held && motion.settling?.isActive == true || motion.gesture?.pending == true), gestureKey = route.key,
        onProgress = { value ->
            motion.gesture?.let { request ->
                request.travel = value
                if (!request.pending && motion.owns(request.generation) && motion.held) {
                    motion.fingerProgress = request.progress()
                    if (!reducedMotion) motion.followFingers(scope)
                }
            }
        },
        onRelease = { cancelled ->
            motion.gesture?.let { request ->
                request.released = true; request.cancelled = cancelled
                if (request.pending) {
                    if (cancelled || request.travel < .35f) request.destination = request.source
                } else settle(request)
            }
        }) { zoomIn, midpoint ->
        if (motion.disposed || motion.takeover) return@pinchToChangeView false
        val predecessor = motion.gesture?.takeUnless { it.finished.isCompleted }
        if (predecessor?.cancelled == true) return@pinchToChangeView false
        val base = if (predecessor?.pending == true) predecessor.destination
            else if (motion.source != null) (if (motion.releaseTarget) motion.target else motion.source) ?: currentStyle
            else currentStyle
        val next = base.zoomStep(zoomIn, with(density) { width.toDp().value }, route.supportsTracklist())
        if (next == base) return@pinchToChangeView false
        val request = BrowsePinchGesture(zoomIn, midpoint, base, next, motion.gestureEpoch)
        motion.gesture = request
        if (motion.source == null && predecessor == null && !motion.readbackPending) {
            val started = startStep(base, request)
            if (!started) { motion.gesture = null; request.finished.complete(Unit) }
            return@pinchToChangeView started
        }
        if (predecessor == null) { motion.gesture = null; return@pinchToChangeView false }
        if (!predecessor.pending && !motion.committed && !motion.capturing && motion.destinationReady &&
            (sameView(motion.source, next) || sameView(motion.target, next))) {
            request.generation = motion.transaction
            request.retargeted = true
            request.origin = motion.progress.value
            request.direction = if (sameView(motion.target, next)) 1f else -1f
            request.fallbackTarget = motion.releaseTarget
            // Retire the old cleanup owner before cancelling its animation.
            motion.settleOwner++
            motion.settling?.cancel(); motion.settling = null
            motion.held = true
            motion.fingerProgress = request.origin
            motion.resumeFingers()
            true
        } else {
            // A further adjacent step needs a fresh source atlas. Keep its complete input,
            // even if it releases before the previous endpoint and header are handed back.
            request.pending = true
            scope.launch {
                var started = false
                try {
                    predecessor.finished.await()
                    if (!motion.disposed && request.epoch == motion.gestureEpoch && motion.source == null && !motion.readbackPending)
                        started = startStep(currentStyle, request)
                } finally {
                    if (!started) {
                        if (motion.gesture === request) motion.gesture = null
                        request.finished.complete(Unit)
                    }
                }
            }
            true
        }
    }
}
