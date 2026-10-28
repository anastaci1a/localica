package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.browse.BrowseStyle
import kotlinx.coroutines.CompletableDeferred

internal data class CollectionHeaderFrame(val generation: Long, val progress: Float, val presented: Rect,
    val live: Rect?, val heroTop: Float?, val destinationReady: Boolean, val handoff: Boolean,
    val source: Rect, val destination: Rect?, val drawn: Boolean, val heroProgress: Float?,
    val retainedPart: FrozenBrowsePart?, val anchorPoint: Float?, val anchorExpected: Float, val anchorEdge: Int,
    val heroArtworkTop: Float?, val heroInset: Float, val parallax: Float,
    val actionBounds: Rect?, val liveAction: Boolean, val actionMaterialDraw: GlassSurfaceDrawObservation?,
    val staticRetainedPart: FrozenBrowsePart?, val sourceActionBounds: Rect?, val displayedHeaderPart: FrozenBrowsePart?)
internal val LocalCollectionHeaderFrameObserver = staticCompositionLocalOf<((CollectionHeaderFrame) -> Unit)?> { null }

/** Public lazy measurements own geometry; only a completed immutable atlas owns retained pixels. */
@OptIn(ExperimentalComposeUiApi::class)
internal class CollectionPinchHeader(val motion: BrowsePinchMotion, val grid: LazyGridState) {
    private val geometry = CollectionHeaderPinch()
    var beyond: BeyondBoundsLayout? = null
    var viewport: LayoutCoordinates? = null
    var background: LayoutCoordinates? = null
    var coordinates: LayoutCoordinates? = null
    var placedStyle: BrowseStyle? = null
    var placedRequest = -1
    var request = 0
    var captureRequest by mutableIntStateOf(0)
    var recordedRequest = -1
    var layer: GraphicsLayer? = null
    var staticLayer: GraphicsLayer? = null
    // The action presentation is never submitted to the asynchronous atlas read.
    // Its original LazyGrid subtree remains the only input/semantics owner.
    var actionCoordinates: LayoutCoordinates? = null
    var actionPlacedRequest = -1
    var actionSpec: CollectionHeaderActionSpec? = null
    var actionPresentation by mutableStateOf<CollectionHeaderActionCapture?>(null)
        private set
    var actionLayer: GraphicsLayer? = null
    var actionRecordedGeneration: Long? = null
    var actionRecordedRect: Rect? = null
    var actionMaterialDraw: GlassSurfaceDrawObservation? = null
    var excludingActionFromCapture = false
        private set
    var pinnable: PinnableContainer? = null
    private var pin: PinnableContainer.PinnedHandle? = null
    private var generation: Long? = null
    private var sourceStyle: BrowseStyle? = null
    private var targetStyle: BrowseStyle? = null
    private var endpointStyle: BrowseStyle? = null
    private var serialAtHandoff = -1
    private var target = CompletableDeferred<Unit>()
    private var handed = CompletableDeferred<Unit>()
    private var frozen: FrozenBrowsePart? = null
    private var frozenStatic: FrozenBrowsePart? = null
    private var verifiedFrame: CollectionHeaderPinch.Frame? = null
    var ordinaryTop: Float? = null
    private var revision by mutableIntStateOf(0)
    var observer: ((CollectionHeaderFrame) -> Unit)? = null
    private var lastMeasurementProblem: String? = null
    private var sourceBounds = Rect.Zero
    private var targetBounds: Rect? = null
    private var heroDrawTop: Float? = null
    private var heroDrawProgress: Float? = null
    private var artworkCoordinates: LayoutCoordinates? = null
    var heroInset = 0f
    var parallax = .65f

    private fun rect(coordinates: LayoutCoordinates): Rect {
        val viewport = checkNotNull(viewport).also { check(it.isAttached) }
        check(coordinates.isAttached)
        return Rect(viewport.localPositionOf(coordinates, Offset.Zero), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }
    /** Read inside the callback: its extra placements can disappear immediately on return. */
    private fun measure(style: BrowseStyle, retain: Boolean = false,
        copyAction: ((Rect, CollectionHeaderActionSpec) -> Unit)? = null): Rect {
        val ticket = ++request
        val mounted = grid.layoutInfo.visibleItemsInfo.any { it.key == "browser-notice" }
        fun copyPlaced(requireTicket: Boolean): Rect? {
            val placedCoordinates = coordinates
            if (placedStyle != style || placedCoordinates?.isAttached != true ||
                requireTicket && placedRequest != ticket) return null
            val copied = rect(placedCoordinates)
            if (copyAction != null) {
                val action = actionCoordinates
                val spec = actionSpec
                if (action?.isAttached != true || spec == null || requireTicket && actionPlacedRequest != ticket) return null
                val offset = placedCoordinates.localPositionOf(action, Offset.Zero)
                val bounds = Rect(offset, Size(action.size.width.toFloat(), action.size.height.toFloat()))
                if (bounds.width <= 0f || bounds.height <= 0f) return null
                // Copy INSIDE the public callback: these extra placements may
                // disappear as soon as measure returns, even with a composition pin.
                copyAction(bounds, spec)
            }
            if (retain && pin == null) pin = checkNotNull(pinnable) { "Header cannot retain its composed source" }.pin()
            return copied
        }
        // A visible first item needs no extra layout. The public beyond-bounds
        // provider can return without visiting its block when there is no Before
        // extent; its absence is not evidence that this placed header is missing.
        if (mounted) copyPlaced(requireTicket = false)?.let { return it }
        return checkNotNull(checkNotNull(beyond) { "Collection header has no public beyond-bounds provider" }
            .layout(BeyondBoundsLayout.LayoutDirection.Before) {
                copyPlaced(requireTicket = true) ?: run {
                    check(hasMoreContent) {
                        "No current placed collection header for $style request=$ticket placedRequest=$placedRequest " +
                            "placedStyle=$placedStyle attached=${coordinates?.isAttached} mounted=$mounted pinned=${pin != null}"
                    }
                    null
                }
            }) { "Public header measurement produced no geometry" }
    }
    fun begin(token: Long, source: BrowseStyle, destination: BrowseStyle) {
        check(generation == null)
        var capturedAction: CollectionHeaderActionCapture? = null
        val measured = measure(source, retain = true) { bounds, spec ->
            capturedAction = CollectionHeaderActionCapture(token, bounds, spec)
        }
        ordinaryTop?.let { check(kotlin.math.abs(it - measured.top) <= .5f) { "Source hero/header coordinate is not measured consistently" } }
        check(geometry.begin(token, measured.geometry()))
        generation = token
        actionRecordedGeneration = null
        actionMaterialDraw = null
        actionPresentation = checkNotNull(capturedAction)
        sourceStyle = source; targetStyle = destination; endpointStyle = null
        sourceBounds = measured; targetBounds = null
        target = CompletableDeferred(); handed = CompletableDeferred()
        frozen = null; frozenStatic = null
        verifiedFrame = null
        captureRequest++
        revision++
    }
    fun freeze(part: FrozenBrowsePart, static: FrozenBrowsePart) {
        check(!motion.readbackPending) { "Header pixels cannot replace a pending native lease" }
        frozen = part; frozenStatic = static
        // The completed image now owns the source pixels. Keeping the old live
        // header pinned through reflow suppresses the fresh offscreen placement
        // needed by the destination's public beyond-bounds measurement.
        pin?.release(); pin = null
        revision++
    }
    suspend fun awaitDestination() = target.await()
    suspend fun awaitHandoff() = handed.await()
    fun beginHandoff(commit: Boolean) {
        val token = checkNotNull(generation)
        val endpoint = if (commit) CollectionHeaderPinch.Endpoint.Destination else CollectionHeaderPinch.Endpoint.Source
        check(geometry.beginHandoff(token, endpoint))
        endpointStyle = if (commit) targetStyle else sourceStyle
        serialAtHandoff = request
    }
    /** Called only after the browser has measured and corrected the real anchor. */
    fun anchorCorrected(style: BrowseStyle) {
        val token = generation ?: return
        if (endpointStyle != null && style != endpointStyle || endpointStyle == null && style != targetStyle) return
        val measured = runCatching { measure(style) }.getOrElse {
            val problem = it.message ?: it.javaClass.simpleName
            val kind = problem.substringBefore(" request=")
            if (lastMeasurementProblem != kind) android.util.Log.w("CollectionHeaderPinch", problem)
            lastMeasurementProblem = kind
            return
        }
        lastMeasurementProblem = null
        if (endpointStyle == null) {
            check(geometry.updateDestination(token, measured.geometry()))
            targetBounds = measured
            revision++
            target.complete(Unit)
        } else if (request > serialAtHandoff) {
            val endpoint = geometry.frame(token, motion.progress.value, measured.geometry())
            if (geometry.retireAfterHandoff(token, motion.progress.value, measured.geometry())) {
                verifiedFrame = endpoint
                reportFrame(drawn = false)
                handed.complete(Unit)
            }
        }
    }
    fun clear() {
        generation?.let { geometry.discard(it) }
        generation = null; actionPresentation = null; actionRecordedGeneration = null; actionRecordedRect = null; actionMaterialDraw = null; frozen = null; frozenStatic = null; verifiedFrame = null; sourceStyle = null; targetStyle = null; endpointStyle = null
        target.cancel(); handed.cancel()
        pin?.release(); pin = null
        revision++
    }
    /** A pending native read must keep the pin, just like its atlas and artwork lease. */
    fun detachPin(): (() -> Unit)? {
        val held = pin ?: return null
        pin = null
        return { held.release() }
    }
    private fun frame(): CollectionHeaderPinch.Frame? {
        revision
        val token = generation ?: return null
        val live = coordinates?.takeIf { it.isAttached }?.let(::rect)
        return geometry.frame(token, motion.progress.value, live?.geometry()) ?: verifiedFrame
    }
    fun actionReady(): Boolean = actionPresentation?.let {
        actionRecordedGeneration == it.generation && collectionHeaderActionPlacementMatches(actionRecordedRect, actionStageRect()) &&
            actionLayer?.let { layer -> !layer.isReleased && layer.size.width > 0 && layer.size.height > 0 } == true
    } == true
    fun actionRect(): Rect? {
        val captured = actionPresentation ?: return null
        val presented = frame()?.presented?.compose() ?: return null
        return collectionHeaderActionRect(sourceBounds, presented, captured.bounds)
    }
    fun actionPlacedRect(coordinates: LayoutCoordinates?): Rect? = coordinates?.takeIf {
        it.isAttached && viewport?.isAttached == true
    }?.let(::rect)
    fun actionStageRect(): Rect? = actionRect()?.inflate(actionPresentation?.spec?.outsetPx?.toFloat() ?: 0f)
    fun recordRetainedHeader(full: () -> Unit, static: () -> Unit) {
        check(!motion.readbackPending)
        full()
        if (actionPresentation == null || !motion.capturing) return
        try {
            excludingActionFromCapture = true
            static()
        } finally { excludingActionFromCapture = false }
    }
    fun presentedTop(): Float? = frame()?.presented?.top
    fun observeHero(top: Float, progress: Float) { heroDrawTop = top; heroDrawProgress = progress }
    fun observeArtwork(coordinates: LayoutCoordinates) {
        artworkCoordinates = coordinates
    }
    fun translation(): Offset = frame()?.liveOffset?.let { Offset(it.x, it.y) } ?: Offset.Zero
    fun heroTop(): Float? {
        val frame = frame() ?: return null
        val background = background?.takeIf { it.isAttached } ?: return null
        val viewport = viewport?.takeIf { it.isAttached } ?: return null
        return background.localPositionOf(viewport, Offset.Zero).y + frame.presented.top
    }
    fun drawFrame(): Pair<Rect, FrozenBrowsePart>? {
        val frame = frame() ?: return null
        val pixels = frozen ?: return null
        val presented = frame.presented.compose()
        // Never expose a hole or throw if a virtualized/offscreen producer has
        // not drawn this frame. The intact capture is a conservative fallback.
        return presented to (frozenStatic?.takeIf { actionReady() } ?: pixels)
    }
    fun reportFrame(drawn: Boolean, liveAction: Boolean = false, displayedPart: FrozenBrowsePart? = null) {
        val observer = observer ?: return
        val frame = frame() ?: return
        val anchor = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == motion.focusKey }
        // The artwork can be outside the clipped viewport and skip drawing.
        // Query its live layer transform here rather than retaining its last draw sample.
        val heroArtworkTop = background?.takeIf { it.isAttached }?.let { parent ->
            artworkCoordinates?.takeIf { it.isAttached }?.let {
                parent.localPositionOf(it, Offset.Zero).y
            }
        }
        observer(CollectionHeaderFrame(checkNotNull(generation), motion.progress.value, frame.presented.compose(),
            coordinates?.takeIf { it.isAttached }?.let(::rect), heroDrawTop, frame.destinationReady, endpointStyle != null,
            sourceBounds, targetBounds, drawn, heroDrawProgress, frozen,
            anchor?.let { it.offset.y + it.size.height * motion.focusFraction }, motion.focusTop.toFloat(), motion.focusEdge,
            heroArtworkTop, heroInset, parallax, actionRect(), liveAction, actionMaterialDraw, frozenStatic, actionPresentation?.bounds, displayedPart))
    }
    private fun Rect.geometry() = CollectionHeaderPinch.Rect(left, top, right, bottom)
    private fun CollectionHeaderPinch.Rect.compose() = Rect(left, top, right, bottom)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.collectionPinchHeader(owner: CollectionPinchHeader, style: BrowseStyle): Modifier {
    val layer = rememberGraphicsLayer()
    val staticLayer = rememberGraphicsLayer()
    val pinnable = LocalPinnableContainer.current
    SideEffect { owner.layer = layer; owner.staticLayer = staticLayer; owner.pinnable = pinnable }
    DisposableEffect(owner, layer) { onDispose {
        if (owner.layer === layer) { owner.layer = null; owner.staticLayer = null; owner.coordinates = null; owner.pinnable = null }
    } }
    return modifierLocalConsumer { ModifierLocalBeyondBoundsLayout.current?.let { owner.beyond = it } }
        .onPlaced { owner.coordinates = it; owner.placedStyle = style; owner.placedRequest = owner.request }
        .graphicsLayer {
            val offset = owner.translation()
            translationX = offset.x; translationY = offset.y
        }.drawWithContent {
            val request = owner.captureRequest
            if ((!owner.motion.readbackPending && (!owner.motion.capturing || owner.recordedRequest != request)) || layer.size == IntSize.Zero) {
                owner.recordRetainedHeader(
                    full = { layer.record { this@drawWithContent.drawContent() } },
                    static = { staticLayer.record { this@drawWithContent.drawContent() } })
                owner.recordedRequest = request
            }
            // Preserve the baseline display-list ownership during pending native
            // capture. Re-entering drawContent could mutate captured descendants.
            if (owner.motion.visualSource == null) drawLayer(layer)
        }
}

internal fun Modifier.collectionPinchOverlay(owner: CollectionPinchHeader?): Modifier = if (owner == null) this else
    drawWithContent {
        drawContent()
        owner.drawFrame()?.let { (rect, pixels) ->
            drawImage(pixels.image, srcOffset = pixels.origin, srcSize = pixels.size,
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1)))
            val live = owner.actionReady()
            if (live) owner.actionStageRect()?.let { action -> owner.actionLayer?.let { layer ->
                translate(action.left, action.top) { drawLayer(layer) }
            } }
            owner.reportFrame(drawn = true, liveAction = live, displayedPart = pixels)
        }
    }
