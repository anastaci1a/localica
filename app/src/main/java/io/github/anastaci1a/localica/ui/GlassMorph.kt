package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.glassShadowInset
import io.github.anastaci1a.localica.ui.components.TriggerRole
import io.github.anastaci1a.localica.ui.components.PopupSharedContent

import io.github.anastaci1a.localica.ui.layout.PopupViewportSlot
import io.github.anastaci1a.localica.ui.spec.PopupViewportInsets
import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight
import io.github.anastaci1a.localica.ui.spec.PopupKind

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec; import io.github.anastaci1a.localica.ui.spec.PageCornerRadius; import io.github.anastaci1a.localica.ui.spec.PopupCornerRadius
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.engine.PopupTakeover.*
import io.github.anastaci1a.localica.ui.PopupEvent.*
import io.github.anastaci1a.localica.ui.PopupPresenter.*
import io.github.anastaci1a.localica.ui.engine.GlassOriginWell
import io.github.anastaci1a.localica.ui.engine.SurfaceView
import io.github.anastaci1a.localica.ui.engine.MotionPixelRect
import io.github.anastaci1a.localica.ui.engine.motionPixelRect
import io.github.anastaci1a.localica.ui.engine.GlassShadowExclusion
import io.github.anastaci1a.localica.ui.engine.GlassShadowReceiver
import io.github.anastaci1a.localica.ui.engine.GlassShadowReceiverField
import io.github.anastaci1a.localica.ui.engine.glassWellCaster

/** The trigger stays in layout while its one visible surface moves into a modal window. */
internal data class GlassOriginPresentation(val shape: GlassShape, val vertical: Boolean,
    val horizontalShift: Float)

/** How the screen currently shows an anchor's resting layout bounds: a uniform scale about [pivot]
 * in the same coordinate space, as when a selected item lifts (#554). */
internal data class GlassLift(val scale: Float, val pivot: Offset) {
    fun map(rect: Rect) = Rect(pivot + (rect.topLeft - pivot) * scale, pivot + (rect.bottomRight - pivot) * scale)
}

@Stable
internal class GlassAnchor : PopupTakeoverOwner {
    var popup: GlassPopupFlight? by mutableStateOf(null); private set
    override val takeover get() = popup?.takeover ?: PopupTakeover.Closed
    fun admit(flight: GlassPopupFlight) { popup = flight }
    fun release(flight: GlassPopupFlight) { if (popup === flight) popup = null }
    // Derived views notify readers only when their own value changes, as the flags they replace did.
    val present by derivedStateOf(structuralEqualityPolicy()) { popup?.present == true }
    val hidden by derivedStateOf(structuralEqualityPolicy()) { expanded }
    val modalPresent by derivedStateOf(structuralEqualityPolicy()) { popup?.modalPresent == true }
    var surfaceNode: SurfaceView? by mutableStateOf(null)
    var returningNode: SurfaceView? = null
    var popupHome: GlassPopupHome? = null
    /** Live presented transform of [bounds], supplied by whatever moves the trigger in draw rather
     * than layout (the selection lift). Null when it is drawn where it is laid out. */
    var presentation: (() -> GlassLift?)? = null
    /** Home geometry for every morph, expand and retract: the trigger as presented, not as laid out. */
    val presentedBounds: Rect get() = bounds.takeIf { it == Rect.Zero } ?: presentation?.invoke()?.map(bounds) ?: bounds
    val presentedScale: Float get() = presentation?.invoke()?.scale ?: 1f
    // Optional source-owned draw pose, evaluated against its home height in px.
    var originPresentation: ((Float) -> GlassOriginPresentation)? = null
    fun clearOriginPresentation(value: (Float)->GlassOriginPresentation) { if(originPresentation===value) originPresentation=null }
    val contact = GlassContact({ modalPresent }, { popup?.progress })
    val searchSlot get() = popup?.searchSlot
    val searchSlotFraction get() = searchSlotFraction(searchSlot)
    val samplingLocation = BackdropLocation()
    // Last physically measured basis, retained if the trigger leaves composition.
    var lastSamplingFootprint: HomeBackdropFootprint? = null
    fun samplingFootprint(): HomeBackdropFootprint? {
        val coordinates = samplingLocation.coordinates?.takeIf { it.isAttached } ?: return null
        return HomeBackdropFootprint.create(
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
            samplingLocation.registrationToScreen())
    }
    /** Opening admission can reclaim a still-present returning window synchronously. */
    val reclaimOpening get() = popup?.reclaimOpening
    var triggerMounted by mutableStateOf(false)
    var restoredDrawn = false
    var restoredDrawRequest by mutableLongStateOf(0L)
    /** The return request whose optical body the restored source took at its draw entry (R5). Not completed-draw proof. */
    var restoredBodyReceipt by mutableLongStateOf(-1L)
    var restoredPresentedReceipt by mutableLongStateOf(-1L)
    var bounds by mutableStateOf(Rect.Zero)
    // The shared shadow group its trigger casts into, if any.
    var shadowPlane: io.github.anastaci1a.localica.ui.engine.ShadowPlane? = null
    var source: FrostedBackdrop? = null
    var face by mutableStateOf<(@Composable () -> Unit)?>(null)
    /** The live record its glass surface draws; null for a geometry-only source (#748). */
    var material by mutableStateOf<State<GlassSurfacePresentation>?>(null)
    val depthScale get() = material?.value?.depthScale ?: RaisedGlassDepthScale
    var cornerRadius: androidx.compose.ui.unit.Dp? = null
    private var triggerRole by mutableStateOf<TriggerRole<*, *>>(TriggerRole.Surface)
    fun bindRole(role: TriggerRole<*, *>) { triggerRole = role }
    val quiet get() = contextual || triggerRole == TriggerRole.PlainIcon
    val chromeTrigger get() = triggerRole == TriggerRole.Navigation
    var contextual by mutableStateOf(false)
    var bottomClearance = 0f
    var bottomLimit = Float.NaN
    var ink by mutableStateOf(Color.Unspecified)
    // A context trigger in a scrolling grid can defer its placement until opening;
    // see contextMenuAnchor. Ordinary anchors follow every global position.
    var contextPlacementTracked by mutableStateOf(true)
    var contextPlacement: (() -> Unit)? = null
    /** Publish a deferred context trigger's current placement, then keep following it. */
    fun prepareContextPlacement() {
        contextPlacement?.invoke()
        contextPlacementTracked = true
    }
}

/** #920: who draws a presentation's pixels; set only in InWindow and Restoring. */
internal enum class PopupPresenter { Window, Offered, Taken, Releasing, Reclaimed }
/** #920 SPEC section 2 events. A window receipt must name the presentation's admitted window. */
internal enum class PopupEvent(val setsWindow: Boolean = false, val windowReceipt: Boolean = setsWindow) { Hide, Admitted(true), AtHome, Show, Returned, Retired,
    Gone, HomeReturns, HomeLeft, WindowGone(windowReceipt = true), OfferReady(windowReceipt = true), SampleLost(windowReceipt = true), OfferTaken, OfferReleased, WindowReplaced(true), Rebind, WindowDrew(windowReceipt = true) }
/** A presentation's sampling basis (SPEC G4): captured at T1, re-captured at T2 and rebound only through [GlassPopupFlight.advance]. */
internal data class SamplingBasis(val source: FrostedBackdrop?, val bounds: Rect, val footprint: HomeBackdropFootprint?, val measured: Boolean)
/** One presentation's stored value. [gen] counts its opens, so a writer registered for an earlier open is refused. */
internal data class PopupState(val takeover: PopupTakeover = Closed, val presenter: PopupPresenter? = null, val gen: Int = 0,
    val window: android.view.Window? = null, val basis: SamplingBasis? = null)

/** SPEC section 3 as phase x presenter x event; a missing triple is refused. T1 is [GlassPopupFlight.open]. */
internal val PopupEdges: Map<Triple<PopupTakeover, PopupPresenter?, PopupEvent>, Pair<PopupTakeover, PopupPresenter?>> = buildMap {
    fun edge(t: PopupTakeover, p: PopupPresenter?, e: PopupEvent, to: PopupTakeover, q: PopupPresenter?) = put(Triple(t, p, e), to to q)
    edge(Admitting, null, Admitted, InWindow, Window) // T2
    for (e in listOf(Hide, Retired, Gone)) edge(Admitting, null, e, Closed, null) // T3, T17
    edge(Admitting, null, Rebind, Admitting, null)
    for (t in listOf(InWindow, Restoring)) {
        for (p in PopupPresenter.entries) {
            for (e in listOf(Retired, Gone, WindowReplaced)) edge(t, p, e, Closed, null) // T17, T16
            edge(t, p, Admitted, InWindow, p) // T2 again at a reclaimed gate
            for (e in listOf(Rebind, WindowGone)) edge(t, p, e, t, p)
            edge(t, p, HomeLeft, t, Window) // the home registration left
        }
        edge(t, Window, OfferReady, t, Offered) // T7
        edge(t, Offered, OfferTaken, t, Taken) // T8
        for (p in listOf(Offered, Taken)) edge(t, p, SampleLost, t, Window) // the window lost the home sample
        edge(t, Offered, HomeReturns, t, Window) // T12
        edge(t, Taken, HomeReturns, t, Releasing) // T13, T15
        edge(t, Releasing, WindowDrew, t, Reclaimed) // C1: this presentation's window drew the body
        edge(t, Reclaimed, OfferReleased, t, Window) // T14
    }
    for (p in PopupPresenter.entries) {
        edge(InWindow, p, AtHome, Restoring, p) // T9
        edge(Restoring, p, Show, InWindow, p) // T11
        edge(Restoring, p, Returned, Closed, null) // T10
    }
}

internal class GlassPopupFlight(private val anchor: GlassAnchor? = null, private val receipts: ((GlassDrawReceipt) -> Unit)? = glassReceipts()) : PopupTakeoverOwner {
    var state by mutableStateOf(PopupState()); private set
    override val takeover by derivedStateOf(structuralEqualityPolicy()) { state.takeover }
    val present get() = takeover != Closed
    val modalPresent get() = takeover >= InWindow
    val gen by derivedStateOf(structuralEqualityPolicy()) { state.gen }
    val window by derivedStateOf(referentialEqualityPolicy()) { state.window }
    val basis by derivedStateOf(referentialEqualityPolicy()) { state.basis }
    val progress = GlassExpansion()
    val searchSlot = SearchSlotMotion(this)
    var reclaimOpening: (() -> Unit)? = null
    /** T1, this presentation's next open. */
    fun open(basis: SamplingBasis) = (takeover == Closed).also { if (it) step(PopupState(Admitting, gen = state.gen + 1, basis = basis), "Open") }
    /** The one writer (S1): a row of [PopupEdges] for the open [gen]; a window receipt names this presentation's admitted window. */
    fun advance(gen: Int, event: PopupEvent, window: android.view.Window? = null, basis: SamplingBasis? = null): Boolean {
        val now = state
        if (gen != now.gen || event.windowReceipt && window == null || window != null && !event.setsWindow && window !== now.window) return false
        val (to, presenter) = PopupEdges[Triple(now.takeover, now.presenter, event)] ?: return false
        step(if (to == Closed) PopupState(gen = now.gen) else PopupState(to, presenter, now.gen,
            if (event == WindowGone) null else window ?: now.window, basis ?: now.basis), event.name)
        return true
    }
    /** A receipt of the current open's draw pass (S1). */
    fun receipt(event: PopupEvent, window: android.view.Window? = null) = advance(state.gen, event, window)
    private fun step(next: PopupState, event: String) {
        val from = state
        if (from.takeover == Closed && next.takeover != Closed) anchor?.admit(this)
        state = next
        if (next.takeover == Closed) anchor?.release(this)
        reportGlass(receipts) { GlassTransitionReceipt(System.identityHashCode(this), next.gen, "${from.takeover}/${from.presenter}", event, "${next.takeover}/${next.presenter}") }
    }
    var layer by mutableStateOf<GraphicsLayer?>(null)
    /** R5 optical body (B and D; the native field stays outside it) and the closing request's withdrawal at an ordinary source's draw entry. */
    var body: OpticalBody? = null
    var sourceDraw: ((DrawScope) -> Unit)? = null
    fun withdrawBody(scope: DrawScope, request: Long) {
        val a = anchor ?: return
        val take = a.restoredBodyReceipt != request
        if (take) { a.restoredBodyReceipt = request; reportGlass(receipts) { GlassBodyReceipt(System.identityHashCode(this), "withdraw", request) } }
        body?.withdraw(scope, take)
    }
    fun refillBody(scope: DrawScope, optics: DrawScope.() -> Unit) { if (body?.refill(scope, optics) == true)
        reportGlass(receipts) { GlassBodyReceipt(System.identityHashCode(this), "unlink", anchor?.restoredDrawRequest ?: -1L) } }
    /** A source draw that did not complete returns its reservation; the popup's next draw refills B. */
    fun rearmBody(request: Long) {
        val a = anchor?.takeIf { it.restoredBodyReceipt == request } ?: return
        a.restoredBodyReceipt = -1L; reportGlass(receipts) { GlassBodyReceipt(System.identityHashCode(this), "rearm", request) }
    }
    var origin = Offset.Zero
    private var homeOrigin = Offset.Zero
    /** The home's projection offset: it tracks the home except while Taken, so the frame both presenters draw (C1) lines up. */
    fun projectionOffset(home: Offset): Offset { if (!handedOff) homeOrigin = home; return origin - homeOrigin }
    var revision by mutableLongStateOf(0L)
    /** The home projects until the window has drawn the body (C1), so no frame lacks the popup. */
    val prepared by derivedStateOf(structuralEqualityPolicy()) { state.presenter == Offered || state.presenter == Taken || state.presenter == Releasing }
    val handedOff by derivedStateOf(structuralEqualityPolicy()) { state.presenter == Taken }
    val wasProjected by derivedStateOf(structuralEqualityPolicy()) { state.presenter == Taken || state.presenter == Releasing || state.presenter == Reclaimed }
}

@Composable
internal fun rememberGlassAnchor(deferContextPlacement: Boolean = false,
    role: TriggerRole<Nothing, Nothing> = TriggerRole.Surface, clearance: androidx.compose.ui.unit.Dp? = null): GlassAnchor {
    val ink = LocalContentColor.current
    val home = LocalGlassPopupHome.current
    val anchor = remember { GlassAnchor().also { it.ink = ink; it.contextPlacementTracked = !deferContextPlacement; it.popupHome = home } }
    if (role == TriggerRole.Contextual) {
        val contextClearance = with(LocalDensity.current) { (clearance ?: LocalBrowseObscuredInset.current).toPx() }
        SideEffect { anchor.contextual = true; anchor.bottomClearance = contextClearance }
    }
    return anchor
}

/** Optional evidence of each published context-trigger placement; no behavior depends on it. */
internal val LocalContextAnchorPlacementObserver = staticCompositionLocalOf<((Rect) -> Unit)?> { null }

/** Context menus return to the actual artwork shape, never an invented menu button.
 * A deferred anchor keeps only its coordinates until prepareContextPlacement, so
 * resting grids do not publish window geometry for every cell on every scroll frame. */
@Composable internal fun Modifier.contextMenuAnchor(anchor: GlassAnchor, circular: Boolean = false): Modifier {
    anchor.captureInk()
    val view = LocalView.current
    val source = LocalFrostedBackdrop.current
    val density = LocalDensity.current
    val clearance = with(density) { LocalBrowseObscuredInset.current.toPx() }
    val observer = LocalContextAnchorPlacementObserver.current
    val home = LocalGlassPopupHome.current
    SideEffect { anchor.popupHome = home; anchor.source = source; anchor.contextual = true; anchor.bottomClearance = clearance; anchor.face = null }
    fun publish(coordinates: LayoutCoordinates) {
        anchor.bounds = Rect(coordinates.backdropPosition(view), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        anchor.bottomLimit = location[1] + view.height - clearance
        anchor.cornerRadius = if (circular) with(density) { (coordinates.size.width / 2f).toDp() } else 16.dp
        anchor.lastSamplingFootprint = anchor.samplingFootprint()
        observer?.invoke(anchor.bounds)
    }
    if (!anchor.contextPlacementTracked) {
        val placed = remember(anchor) { arrayOfNulls<LayoutCoordinates>(1) }
        SideEffect {
            anchor.contextPlacement = {
                placed[0]?.takeIf { it.isAttached }?.let { coordinates ->
                    // The same global sample backdropLocation records, then the trigger geometry.
                    val location = anchor.samplingLocation
                    val screen = IntArray(2)
                    view.getLocationOnScreen(screen)
                    location.windowOffset = Offset(screen[0].toFloat(), screen[1].toFloat())
                    location.fallback = coordinates.positionInRoot() + location.windowOffset
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { location.transformRevision++ }
                    publish(coordinates)
                }
            }
        }
        return onPlaced {
            placed[0] = it
            anchor.samplingLocation.coordinates = it
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { anchor.samplingLocation.placement++ }
        }
    }
    SideEffect { anchor.contextPlacement = null }
    return quietGlassCaster(anchor).backdropLocation(anchor.samplingLocation).onGloballyPositioned { publish(it) }
}

@Composable
internal fun GlassAnchor?.captureInk() {
    val color = LocalContentColor.current
    SideEffect { this?.ink = color }
}

internal fun GlassAnchor.refreshSource() { source?.refresh() }

private fun TriggerRole.LyricsHeader<GlassSplitContact, GlassOriginPreparation>.presentation(height: Float): GlassOriginPresentation {
    val separation = originGap(preparation.progress)
    val left = if (side < 0f) contacts.left else contacts.right
    val right = if (side < 0f) contacts.right else contacts.left
    val common = contacts.presentation(contacts.left, contacts.right, 0f).let { if (contacts.left.vertical) it.copy(strain = -it.strain) else it }
    return GlassOriginPresentation(contacts.presentation(left, right, separation), left.vertical,
        side * height / 2f * (common.scaleX() - 1f) * (1f - separation))
}

/** A selection item supplies existing geometry data; the popup system owns its origin mapping. */
@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor?, role: TriggerRole.SelectionItem<SelectionEntry>): Modifier {
    val view = LocalView.current
    val entry = role.entry
    SideEffect {
        anchor?.presentation = { entry.presented?.let { lift -> entry.coordinates?.takeIf { it.isAttached }
            ?.let { GlassLift(lift.scale, it.backdropPosition(view) + lift.pivot) } } }
    }
    return this
}

/** Material and retained roles expose only their valid registration cases. */
@Composable
internal fun GlassAnchor?.glassAnchor(role: TriggerRole.MaterialSurface<ShadowPlane>): ((SurfaceView, Boolean) -> Unit)? =
    registerGlassSurface(role.plane, material = true)

@Composable
internal fun GlassAnchor?.glassAnchor(role: TriggerRole.RetainedSurface<ShadowPlane>): ((SurfaceView, Boolean) -> Unit)? =
    registerGlassSurface(role.plane, material = false)

/** One node-registration implementation for both existing surface lifetimes. */
@Composable
private fun GlassAnchor?.registerGlassSurface(plane: ShadowPlane, material: Boolean): ((SurfaceView, Boolean) -> Unit)? {
    val anchor = this ?: return null
    if (material) DisposableEffect(plane, anchor) {
        anchor.shadowPlane = plane
        onDispose { if (anchor.shadowPlane === plane) anchor.shadowPlane = null }
    }
    return { node, bound ->
        if (bound) {
            anchor.surfaceNode = node
            anchor.returningNode = node
            if (!material) anchor.shadowPlane = plane
        } else if (anchor.surfaceNode === node) {
            anchor.surfaceNode = null
            if (node.windowRetained().not()) anchor.returningNode = null
        }
    }
}

/** Material forwarding shares the binding writer without installing another anchor. */
@Composable
private fun Modifier.bindGlassSurface(anchor: GlassAnchor?, source: FrostedBackdrop?, cornerRadius: androidx.compose.ui.unit.Dp?): Modifier {
    val binding = foldIn<GlassDepthMarker?>(null) { found, element -> if (element is GlassDepthMarker) element else found }
    SideEffect { if (anchor != null) {
        anchor.source = binding?.source ?: source
        anchor.cornerRadius = if (binding?.role == TriggerRole.PlainIcon) null else cornerRadius
    } }
    return this
}

@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor?, source: FrostedBackdrop? = null,
    role: TriggerRole<GlassSplitContact, GlassOriginPreparation> = TriggerRole.Surface,
    face: (@Composable () -> Unit)? = null): Modifier {
    if (anchor == null && role !is TriggerRole.LyricsHeader) return this
    val originPresentation: ((Float) -> GlassOriginPresentation)? =
        if (role is TriggerRole.LyricsHeader) remember(role) { { height: Float -> role.presentation(height) } } else null
    val marker = GlassDepthMarker(anchor, originPresentation, source, role)
    val presented = if (role is TriggerRole.LyricsHeader) drawWithContent {
        translate(left = if (role.separated) role.presentation(CompactControlSize.toPx()).horizontalShift else 0f) { this@drawWithContent.drawContent() }
    } else this
    if (anchor == null) return if (role !is TriggerRole.LyricsHeader) this else presented.then(marker)
    originPresentation?.let { presentation ->
        DisposableEffect(anchor, presentation) {
            anchor.originPresentation = presentation
            onDispose { anchor.clearOriginPresentation(presentation) }
        }
    }
    val view = LocalView.current
    val home = LocalGlassPopupHome.current
    SideEffect {
        anchor.popupHome = home
        if (source != null) anchor.source = source
        if (face != null) anchor.face = face
        anchor.bindRole(role)
        if (role == TriggerRole.PlainIcon) anchor.cornerRadius = null
    }
    DisposableEffect(anchor) { anchor.triggerMounted = true; onDispose { anchor.triggerMounted = false } }
    return presented.then(if (anchor.quiet) Modifier.quietGlassCaster(anchor) else Modifier).then(marker).backdropLocation(anchor.samplingLocation).onGloballyPositioned {
        anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        anchor.lastSamplingFootprint = anchor.samplingFootprint()
        if (anchor.contextual) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            anchor.bottomLimit = location[1] + view.height - anchor.bottomClearance
        }
    }
        // Read visibility in drawing, not only in a child RenderNode's alpha. Recorded
        // backdrop parents must re-record the restored trigger even while stationary.
        .drawWithContent {
            val restoreRequest = anchor.restoredDrawRequest
            if (!anchor.hidden) {
                val flight = anchor.popup
                flight?.sourceDraw?.invoke(this)
                var drawn = false; try { drawContent(); drawn = true } finally { if (!drawn) flight?.rearmBody(restoreRequest) }
                anchor.restoredDrawn = true
                view.post { if (anchor.restoredDrawRequest == restoreRequest) anchor.restoredPresentedReceipt = restoreRequest }
            }
        }
        .then(if (anchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)
}

/** Carry the material profile with its trigger, including wrappers around shared buttons. */
private data class GlassDepthMarker(val anchor: GlassAnchor?, val origin: ((Float) -> GlassOriginPresentation)? = null,
    val source: FrostedBackdrop? = null, val role: TriggerRole<*, *> = TriggerRole.Surface) : Modifier.Element
@Composable
internal fun Modifier.glassSurfaceAnchor(source: FrostedBackdrop?, cornerRadius: androidx.compose.ui.unit.Dp?): GlassAnchor? {
    val anchor = glassSurfaceAnchor()
    bindGlassSurface(anchor, source, cornerRadius)
    return anchor
}
internal fun Modifier.glassSurfaceAnchor(): GlassAnchor? = foldIn<GlassAnchor?>(null) { anchor, element ->
    if (element is GlassDepthMarker) element.anchor else anchor
}
internal fun Modifier.glassDepthScale(): Float = foldIn(1f) { depth, element ->
    if (element is GlassDepthMarker) element.anchor?.depthScale ?: depth else depth
}

/** A named trigger carries its shared contact policy into the material host. */
internal fun Modifier.glassSurfaceContactPresentation(): (() -> GlassShape)? =
    foldIn<((Float) -> GlassOriginPresentation)?>(null) { origin, element ->
        if (element is GlassDepthMarker) element.origin ?: origin else origin
    }?.let { origin -> { origin(0f).shape } }

internal enum class GlassPlacement { Search, Menu, Center, Sheet, Page }
internal val LocalGlassPanelReady = compositionLocalOf { true }

/** Optional committed-scope observations; no progress subscription or behavior switch. */
internal val LocalGlassMorphWorkObserver =
    staticCompositionLocalOf<((String, android.view.Window?, Float?) -> Unit)?> { null }
// Only separate modal windows may read this scene. Inline lenses must keep using
// LocalFrostedBackdrop, whose recordings exclude their own foreground controls.
internal val LocalModalBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }
private val LocalChildPanels = staticCompositionLocalOf<MutableIntState?> { null }

/** At a dismissal event, a window showing an IME keeps focus until the commit, so the hiding IME ends there (#545). */
private fun android.view.Window.glassInput(enabled: Boolean, event: Boolean = false) {
    val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or if (event && ViewCompat.getRootWindowInsets(decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true) 0 else android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (attributes.flags and flags == if (enabled) 0 else flags) return
    if (enabled) clearFlags(flags) else addFlags(flags)
}

/** #854 §2: the one place a popup surface's motion kind is chosen, from its placement and its trigger's role, never by a screen (#884).
 * A menu from the navigation chrome is the bottom menu; Search is the Base-variant dialog row (#960); Pages and popup sheets stay Legacy. It reads snapshot state only,
 * so the popup recomposes when a role binds; [settledPopupKind] applies a change only while the popup is not present. */
internal fun GlassPlacement.popupKind(anchor: GlassAnchor): PopupKind = when (this) {
    GlassPlacement.Menu -> if (anchor.contextual) PopupKind.RowMenu else if (anchor.chromeTrigger) PopupKind.BottomMenu else PopupKind.Menu
    GlassPlacement.Center, GlassPlacement.Search -> PopupKind.Dialog
    GlassPlacement.Page, GlassPlacement.Sheet -> PopupKind.Legacy
}

/** A menu whose trigger has not been measured yet is centred (a detached dialog) until its first placement. */
internal fun GlassAnchor.menuPlacement(): GlassPlacement = if (bounds == Rect.Zero) GlassPlacement.Center else GlassPlacement.Menu

@Composable
internal fun GlassMenu(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    originPreparation: GlassOriginPreparation? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassMorphPopup(visible, anchor, onDismiss, anchor.menuPlacement(),
        originPreparation = originPreparation) {
        GlassMenuContent(Modifier.fillMaxWidth(), content)
    }
}

/** Keep the existing menu gutters inside the scroll viewport. Padding outside it
 * cuts child shadows at a straight inset line instead of the popup's rounded edge. */
@Composable
internal fun GlassMenuContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.controlVerticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
}

/** Keep this call composed when closed so the same surface can return to its trigger. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    placement: GlassPlacement = GlassPlacement.Menu, darkOverride: Boolean? = null,
    fitContent: Boolean = placement == GlassPlacement.Menu,
    originPreparation: GlassOriginPreparation? = null, searchDismissOnImeBack: Boolean = false,
    sharedContent: PopupSharedContent? = null,
    heightSizing: GlassDialogHeight = GlassDialogHeight.Content,
    content: @Composable () -> Unit) {
    require(heightSizing == GlassDialogHeight.Content || placement == GlassPlacement.Sheet)
    // Lifetime contract: a presentation reports its transitions to the sink selected when it was created (tests set the probe before composing).
    val popupFlight = glassDrawObserver().let { receipts -> remember(anchor) { GlassPopupFlight(anchor, receipts) } }
    val popupKind = settledPopupKind(anchor, placement.popupKind(anchor), popupFlight.present)
    require(sharedContent == null || sharedContent.motion.kind == popupKind) { "Shared content runs its popup's motion kind" }
    @Suppress("NAME_SHADOWING")
    val content: @Composable () -> Unit = remember(content, popupKind) { { CompositionLocalProvider(LocalPopupKind provides popupKind, content = content) } }
    val retainedHeightSizing = remember(anchor, placement) { heightSizing }
    require(retainedHeightSizing == heightSizing) { "Height sizing is fixed for a popup presentation" }
    val popupHome = LocalGlassPopupHome.current
    val requestedVisible = visible
    // A popup leaves its window only when a departing page takes it over (#926).
    val returningInline = popupHome?.departing == true || popupFlight.handedOff && !requestedVisible
    @Suppress("NAME_SHADOWING")
    val visible = requestedVisible && popupHome?.departing != true
    // The screen layer supplies departing motion; the return keeps its original basis.
    val returningHome = if (returningInline) remember(popupHome) { anchor.presentedBounds }
        else null
    val returningFootprint = if (returningInline) remember(popupHome) { anchor.samplingFootprint() ?: anchor.lastSamplingFootprint } else null
    val childHome = rememberGlassPopupHome(!visible)
    val reduced = LocalReducedMotion.current
    val originWell = remember { GlassOriginWell() }
    val receiverField = remember { GlassShadowReceiverField() }
    val shadowReceiver = remember { arrayOfNulls<GlassShadowReceiver>(1) }
    val shadowExclusion = remember { arrayOfNulls<GlassShadowExclusion>(1) }
    val shadowSpread = LocalGlassAppearance.current.shadowSpread
    val wellCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val wellStrength = LocalGlassAppearance.current.shadowStrength
    val speed = LocalMotionSpeed.current
    val progress = popupFlight.progress
    val continuousOrigin = originPreparation.takeUnless { placement == GlassPlacement.Search }
    val motion = sharedContent?.motion ?: rememberPopupWindowMotion(anchor, continuousOrigin != null, anchor.quiet, popupKind)
    DisposableEffect(motion, continuousOrigin) {
        progress.bind(motion.expansion, motion.popupCurve)
        continuousOrigin?.bind(motion.expansion.phase, motion.preparationCurve)
        onDispose { progress.unbind(motion.expansion); continuousOrigin?.reset() }
    }
    var measuredOriginWindow by remember { mutableStateOf<android.view.Window?>(null) }
    val searchObserver = LocalSearchMorphObserver.current
    val body = rememberGlassBody({ anchor.contact.travel + progress.value },
        contact = { if (anchor.contact.pressed) 1f else 0f }, state = anchor.contact.state,
        active = { anchor.contact.modal }, point = { anchor.contact.point })
    var nativePlane by remember { mutableStateOf<ShadowPlane?>(null) }
    var nativeNode by remember { mutableStateOf<SurfaceView?>(null) }
    var leasedNode: SurfaceView? by remember { mutableStateOf(null) }
    val nativeLease = remember { arrayOfNulls<()->Unit>(1) }
    DisposableEffect(anchor) { onDispose { nativeLease[0]?.invoke(); nativeLease[0]=null } }
    val gen = popupFlight.gen
    val origin = popupFlight.basis?.bounds ?: Rect.Zero
    var modalWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var dismissalAttempt by remember { mutableIntStateOf(0) }
    val gestures = rememberModalGestureScope()
    val originOwnsInput = continuousOrigin == null || popupFlight.expanded
    DisposableEffect(gestures, visible, dismissalAttempt, originOwnsInput) {
        if (visible && originOwnsInput) gestures.activate() else gestures.deactivate()
        onDispose { gestures.deactivate() }
    }
    val searchMotion = remember { SearchMorphMotion() }
    SideEffect { searchMotion.observer = searchObserver }
    fun show(visible: Boolean) {
        val departingHold = popupHome?.departing == true && popupHome.mounted && !anchor.chromeTrigger
        if (!visible && popupFlight.present && (departingHold || !childHome.handedOff)) motion.hold() else motion.release()
        motion.show(visible)
    }
    SideEffect { motion.updateSpeed(speed); show(visible) }
    var searchHeightLimit by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    val dismissNow: () -> Unit = {
        // Hide releases semantics at the event; a refusing caller re-shows on its next composition.
        show(false)
        modalWindow?.glassInput(false, event = true)
        gestures.deactivate()
        onDismiss()
        dismissalAttempt++
    }
    val ambientSource = LocalModalBackdrop.current
    androidx.activity.compose.BackHandler(visible && originPreparation != null && (if (continuousOrigin != null) !popupFlight.expanded else !popupFlight.present)) { dismissNow() }
    val transitionJob = remember { arrayOfNulls<Job>(1) }
    val reclaimOpening = remember(anchor, gen, continuousOrigin, placement.takeIf { continuousOrigin != null }) { {
        // A click updates its conditional caller before the next composition can
        // remount the retained entry. Stop the old return at that admission point,
        // otherwise its restored-draw acknowledgment can dispose this same window.
        if (continuousOrigin == null && modalWindow != null && modalWindow === popupFlight.window &&
            popupFlight.advance(gen, PopupEvent.Show)) {
            transitionJob[0]?.cancel()
            transitionJob[0] = null
        }
    } }
    DisposableEffect(anchor, reclaimOpening) {
        popupFlight.reclaimOpening = reclaimOpening
        onDispose { if (popupFlight.reclaimOpening === reclaimOpening) popupFlight.reclaimOpening = null }
    }
    // A reopen that is not a click (state, Back stack, programmatic) reaches the retained
    // window here, in the same apply pass and before this frame draws (#858).
    SideEffect { if (visible) reclaimOpening() }
    // Native admission and return use real placement/draw receipts. Canceling this
    // adapter does not replace the expansion or its physical rectangle. The owner changes only while not present
    // (settledPopupKind); keying on it anyway means this adapter can never drive a disposed owner (#854 review).
    LaunchedEffect(motion, visible, reduced, speed, dismissalAttempt) {
        transitionJob[0] = currentCoroutineContext().job
        if (!visible && !popupFlight.present) return@LaunchedEffect
        // T1 registers the home from the trigger's actual footprint with the same intent as its bounds,
        // so pre-admission draws never fall back to its logical bounds; the gate re-captures (#869).
        if (visible) anchor.samplingFootprint().let { popupFlight.open(SamplingBasis(ambientSource ?: anchor.source, anchor.presentedBounds, it, it != null)) }
        val gen = popupFlight.state.gen
        // An open withdrawn before admission took nothing, so nothing is restored; its window retires now (T3).
        if (!visible) popupFlight.advance(gen, PopupEvent.Hide)
        // Descendant native windows must enter this home before its opacity can move.
        if (!visible) snapshotFlow { childHome.handedOff }.first { it }
        show(visible)
        snapshotFlow { motion.bounds != null || !popupFlight.present }.first { it }
        gateLoop@ while (true) {
            snapshotFlow { motion.expansion.awaitingNativeGate || motion.expansion.retirementReady }.first { it }
            if (motion.expansion.awaitingNativeGate) {
                val generation = motion.expansion.nativeGeneration
                val opening = requireNotNull(motion.expansion.nativeOpening)
                suspend fun gateCurrent() = motion.expansion.awaitNativeGate(generation, opening)
                if (motion.expansion.nativeOpening == true && visible) {
                    continuousOrigin?.beginOriginProof(generation)
                    snapshotFlow { modalWindow != null && measuredOriginWindow === modalWindow }.first { it }
                    if (!gateCurrent()) continue@gateLoop
                    continuousOrigin?.awaitDrawn()
                    if (!gateCurrent()) continue@gateLoop
                    val window = requireNotNull(modalWindow)
                    val plane = requireNotNull(nativePlane)
                    val node = requireNotNull(nativeNode)
                    if (leasedNode !== node) {
                        nativeLease[0]?.invoke()
                        nativeLease[0]=node.retainNativeWindow()
                        leasedNode=node
                    }
                    node.followWindow(motion)
                    node.transferWindow(plane)
                    window.decorView.invalidate()
                    snapshotFlow { node.ownsWindow(plane) }.first { it }
                    if (!gateCurrent()) continue@gateLoop
                    popupFlight.basis?.source?.refresh()
                    popupFlight.advance(gen, PopupEvent.Admitted, window, popupFlight.basis?.copy(footprint = anchor.samplingFootprint(),
                        measured = anchor.samplingLocation.coordinates?.isAttached == true))
                    // A closing gate can finish after visibility reversed; its
                    // input release must not outlive the new native admission.
                    if (continuousOrigin == null) {
                        window.glassInput(true); gestures.activate()
                    }
                } else if (motion.expansion.nativeOpening == false && visible && continuousOrigin == null &&
                    popupFlight.expanded && popupFlight.modalPresent && modalWindow != null && modalWindow === popupFlight.window) {
                    // A reopen reclaimed this window at its return gate (#858). Pass the gate
                    // without restoring the trigger; the reversed expansion then admits it again.
                } else if (motion.expansion.nativeOpening == false) {
                    modalWindow?.glassInput(false)
                    gestures.deactivate()
                    anchor.restoredDrawn = false
                    val receipt = ++anchor.restoredDrawRequest
                    continuousOrigin?.beginOriginProof(generation)
                    // R5: only this request's actual source draw entry takes the optical body.
                    val withdrawal: (DrawScope) -> Unit = { if (anchor.popup === popupFlight && popupFlight.gen == gen && !motion.shown && popupFlight.takeover == Restoring &&
                        motion.expansion.nativeGateCurrent(generation, false) && anchor.restoredDrawRequest == receipt) popupFlight.withdrawBody(it, receipt) }
                    if (continuousOrigin != null) continuousOrigin.withdrawal = withdrawal else popupFlight.sourceDraw = withdrawal
                    popupFlight.advance(gen, PopupEvent.AtHome)
                    // The request ends at its return or the six-frame allowance; a newer request keeps its own withdrawal.
                    try { awaitTriggerReturn {
                        snapshotFlow { !anchor.triggerMounted || anchor.restoredPresentedReceipt == receipt }.first { it }
                        if (!gateCurrent()) return@awaitTriggerReturn
                        continuousOrigin?.awaitDrawn { anchor.triggerMounted }
                        if (!gateCurrent()) return@awaitTriggerReturn
                        val node=leasedNode
                        if (node!=null) while (anchor.triggerMounted) {
                            if (!gateCurrent()) return@awaitTriggerReturn
                            val home=node.returnPlane() ?: break
                            node.transferWindow(home)
                            home.view?.invalidate()
                            snapshotFlow { !anchor.triggerMounted || node.ownsWindow(home) || node.returnPlane()!==home }.first { it }
                            if (!gateCurrent()) return@awaitTriggerReturn
                            if (node.ownsWindow(home)) {
                                val fieldReady = node.awaitWindowField(home)
                                if (!gateCurrent()) return@awaitTriggerReturn
                                if (fieldReady) break
                            }
                        }
                    } } finally { if (continuousOrigin?.withdrawal === withdrawal) continuousOrigin.withdrawal = null
                        if (popupFlight.sourceDraw === withdrawal) popupFlight.sourceDraw = null }
                    if (!gateCurrent()) continue@gateLoop
                    // Keep one frame in which the restored trigger is drawn while this modal
                    // still owns the window, so a reopen there reclaims the same dialog (#858).
                    // The modal draws no body (#847 skip) and no field (home owns it) here.
                    // Only a trigger that presented its receipt has such a frame; an undrawn
                    // origin retires at the #836 six-frame deadline without another frame.
                    if (anchor.triggerMounted && anchor.restoredPresentedReceipt == receipt) {
                        awaitReturnedPresentation()
                        if (!gateCurrent()) continue@gateLoop
                    }
                    // A reopen keeps Restoring and its window; the opening gate admits it again (T11).
                    if (!visible) popupFlight.advance(gen, PopupEvent.Returned)
                }
                if (!gateCurrent()) continue@gateLoop
                motion.expansion.acknowledgeNativeGate(generation)
            } else {
                if (!visible) {
                    nativeLease[0]?.invoke(); nativeLease[0]=null; leasedNode=null
                    popupFlight.advance(gen, PopupEvent.Retired)
                    searchMotion.rendered = Rect.Zero; searchMotion.lastIme = 0
                }
                searchMotion.flight = null
                break
            }
        }
    }
    DisposableEffect(anchor) { onDispose { popupFlight.advance(popupFlight.state.gen, PopupEvent.Gone) } }
    // The home is gone: the popup is Closed at the event, whatever it was doing (T17).
    if (popupHome?.mounted == false) SideEffect { popupFlight.advance(gen, PopupEvent.Gone) }
    val basis = popupFlight.basis
    if (!popupFlight.present || popupHome?.mounted == false || basis == null) return
    // Flat None retires the optical descriptor without retiring this window.
    // Rebind in this composition, before drawing, rather than restarting its
    // flight or waiting for an effect to publish a replacement source.
    val flat = flatGlass()
    fun retired(backdrop: FrostedBackdrop?) = backdrop != null &&
        (backdrop.hasGlassLayer == flat || backdrop.isRetired)
    val currentSource = basis.source?.takeUnless(::retired)
        ?: ambientSource?.takeUnless(::retired)
        ?: anchor.source?.takeUnless(::retired)
    SideEffect { if (basis.source !== currentSource) popupFlight.advance(gen, PopupEvent.Rebind, basis = basis.copy(source = currentSource)) }
    // While the home projects this flight, its layers sample only a source outside that home (#925).
    val departingSource = (if (anchor.chromeTrigger) anchor.source else popupHome?.backdrop)
        .takeIf { returningInline || popupFlight.wasProjected }?.takeUnless(::retired)
    // Navigation keeps the native window in place until this scene is ready.
    // Ordinary popup input must not continuously capture the full-screen
    // backdrop: that readback can starve drag cleanup and fresh fling admission.
    BackdropLease(departingSource)
    val homeSampleReady = flatGlass() || (departingSource?.recorded == true &&
        (android.os.Build.VERSION.SDK_INT >= 33 || departingSource.fallback != null))
    val samplingSource = departingSource.takeIf { homeSampleReady } ?: currentSource
    val homeSource = if (retired(anchor.source)) currentSource else anchor.source
    val flightLayer = rememberGraphicsLayer()
    val opticalBody = rememberOpticalBody()
    DisposableEffect(popupHome, popupFlight, flightLayer) {
        popupFlight.layer = flightLayer
        popupFlight.body = opticalBody
        popupHome?.flights?.add(popupFlight)
        onDispose {
            popupHome?.flights?.remove(popupFlight)
            // Resource lifetime, not an open's step: the registration (and below, the window) leaving resets whichever open holds it now.
            popupFlight.layer = null; popupFlight.body = null; popupFlight.advance(popupFlight.state.gen, PopupEvent.HomeLeft)
        }
    }
    SideEffect { if (!returningInline) popupFlight.advance(gen, PopupEvent.HomeReturns) }
    // One popup appearance owner (#748): an explicit host intent, then the source's own material, its measured page,
    // the caller's page and the detached sampling scene, which can span pages, last; the theme only without any page.
    // The measurement is read first, as at base: this popup recomposes as its trigger moves, whatever owns its appearance.
    val measured = anchor.bounds != Rect.Zero
    val popupLightMix = darkOverride?.let { if (it) 1f else 0f } ?: anchor.material?.value?.lightMix
        ?: anchor.source?.takeIf { measured }?.lightMix ?: LocalFrostedBackdrop.current?.lightMix
        ?: currentSource?.lightMix ?: if (MaterialTheme.colorScheme.surface.luminance() < .5f) 1f else 0f
    val panelDark = popupLightMix >= .5f
    val parentPanels = LocalChildPanels.current
    val childPanels = remember { mutableIntStateOf(0) }
    val countsAsPanel = continuousOrigin == null || popupFlight.modalPresent
    DisposableEffect(parentPanels, countsAsPanel) {
        if (countsAsPanel) parentPanels?.let { it.intValue++ }
        onDispose { if (countsAsPanel) parentPanels?.let { it.intValue-- } }
    }
    Dialog(onDismissRequest = { if (continuousOrigin == null || popupFlight.modalPresent) dismissNow() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { ModalGestureWindow(gestures) { TextEditingFocusHost {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val windowPlane = rememberShadowPlane()
        val windowNode = anchor.surfaceNode ?: leasedNode ?: rememberWindowCaster(windowPlane,anchor)
        val nativeField=rememberNativeSurfaceField(windowPlane,windowNode,shadowSpread)
        SideEffect { nativePlane=windowPlane; nativeNode=windowNode }
        val workObserver = LocalGlassMorphWorkObserver.current
        if (workObserver != null) SideEffect { workObserver("dialog", window, null) }
        if (placement == GlassPlacement.Search) PreserveSearchImeState(window)
        // Attachment follows the current owner, never one captured with an earlier window (#854 review).
        DisposableEffect(window, motion) {
            window?.let { motion.attached(it, windowPlane) }
            onDispose { }
        }
        DisposableEffect(window) {
            modalWindow = window
            if (continuousOrigin != null && (!popupFlight.modalPresent || popupFlight.window !== window)) {
                window?.glassInput(false)
                // A replacement window cannot borrow the old window's admission.
                // Its old frozen surface is gone; restore the trigger and close.
                if (popupFlight.advance(gen, PopupEvent.WindowReplaced, window)) dismissNow()
            }
            // We draw our own animated scrim. A zero-alpha native dim layer still
            // blocks touches as an untrusted occluder during return on Android 16.
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            onDispose {
                if (measuredOriginWindow === window) measuredOriginWindow = null
                popupFlight.advance(popupFlight.state.gen, PopupEvent.WindowGone, window)
                if (nativePlane === windowPlane) {
                    sharedContent?.destination(null)
                    nativePlane=null; nativeNode=null
                }
                if (modalWindow === window) modalWindow = null
            }
        }
        DisposableEffect(window, visible, dismissalAttempt, originOwnsInput) {
            // A busy editor may refuse dismissal; Settings Back may only change
            // the page. Reconcile with the actual state after that callback.
            window?.glassInput(visible && (continuousOrigin == null || popupFlight.expanded && popupFlight.window === window))
            onDispose { }
        }
        @Suppress("DEPRECATION")
        val windowDark = panelDark
        LaunchedEffect(window, windowDark) {
            window?.let {
                // A dismissed panel may finish drawing, but no longer owns input.
                // Otherwise its transparent full-screen window blocks the next action.
                it.statusBarColor = android.graphics.Color.TRANSPARENT
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    it.isStatusBarContrastEnforced = false; it.isNavigationBarContrastEnforced = false
                }
                androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !windowDark
                    isAppearanceLightNavigationBars = !windowDark
                }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(visible) { if (!visible) keyboard?.hide() }
        val admittedDraw = continuousOrigin == null || popupFlight.modalPresent && popupFlight.window === window
        var foregroundReady by remember { mutableStateOf(false) }
        var panelReady by remember { mutableStateOf(false) }
        LaunchedEffect(motion) {
            snapshotFlow { progress.value }.collect { p -> foregroundReady=p>=.85f; panelReady=p>=.9f }
        }
        // A dragged slider rises the whole window body clear of its cancel target (#539).
        val sliderCancel = rememberSliderCancelHost()
        Box(Modifier.fillMaxSize()
            .onGloballyPositioned { popupFlight.origin = it.backdropPosition(view) }
            .nativePlaneReceipt(windowPlane)
            .drawWithContent {
                if (popupHome == null || !returningInline && !popupFlight.wasProjected) drawContent()
                else {
                    flightLayer.record { this@drawWithContent.drawContent() }
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                        popupFlight.revision++
                        if (returningInline) popupFlight.receipt(if (homeSampleReady && samplingSource === departingSource) PopupEvent.OfferReady else PopupEvent.SampleLost, window)
                    }
                    if (!returningInline || !popupFlight.handedOff) drawLayer(flightLayer)
                    else return@drawWithContent
                    // C1: reported only after this window drew the body.
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { popupFlight.receipt(PopupEvent.WindowDrew, window) }
                }
            }
            .then(if (returningInline) Modifier.clearAndSetSemantics { } else Modifier)
            .then(if (continuousOrigin != null) Modifier
                .drawWithContent { if (admittedDraw) drawContent() }
                .then(if (!admittedDraw) Modifier.clearAndSetSemantics { } else Modifier)
                else Modifier)
            .drawBehind { drawRect(Color.Black.copy(alpha = .16f * progress.value)) }
            .pointerInput(visible, admittedDraw) { detectTapGestures { if (visible && admittedDraw) dismissNow() } }) {
            CompositionLocalProvider(LocalSliderCancelHost provides sliderCancel) {
            val viewportSized = heightSizing is GlassDialogHeight.ViewportFraction
            PopupViewportSlot(heightSizing, when {
                placement == GlassPlacement.Page -> PopupViewportInsets.None
                viewportSized -> PopupViewportInsets.SystemBarsAndCutout
                else -> PopupViewportInsets.SafeDrawingAndIme
            }, Modifier
                .offset { androidx.compose.ui.unit.IntOffset(0, -sliderCancel.shift.value.roundToInt()) }) {
                // Geometry still observes every sample in its own layout-content scope.
                // Window/input ownership above does not subscribe to the animation clock.
                fun phase() = progress.value
                fun geometry() = motion.geometryCurve.at(motion.expansion.phase.value())
                val density = LocalDensity.current
                var windowOrigin by remember { mutableStateOf(Offset.Zero) }
                var targetHeight by remember { mutableIntStateOf(0) }
                var viewportTargetHeight by remember { mutableIntStateOf(0) }
                val page = placement == GlassPlacement.Page
                val margin = density.popupMarginPx(page)
                val availableWidth = constraints.maxWidth
                val availableHeight = constraints.maxHeight
                // Native inset/resize passes can temporarily consume either axis.
                // Keep the same composition, but do not turn that absence of space
                // into a new animation target or a recorded home/search rectangle.
                val hasViewport = availableWidth > 0 && availableHeight > 0
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val viewportIdentity = Triple(configuration.screenWidthDp, configuration.screenHeightDp, density.fontScale)
                var previousViewport by remember { mutableStateOf(viewportIdentity) }
                // Keep the keyboard-open viewport after IME dismissal. A nested
                // action menu freezes its parent's position until it has returned.
                val imeHeight = if (viewportSized) 0 else WindowInsets.ime.getBottom(density)
                val imeSource = if (viewportSized) 0 else WindowInsets.imeAnimationSource.getBottom(density)
                val imeTarget = if (viewportSized) 0 else WindowInsets.imeAnimationTarget.getBottom(density)
                val safeBottom = WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(density)
                val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                val nativeIme = ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                SideEffect {
                    if (hasViewport && placement == GlassPlacement.Search && imeHeight > 0)
                        searchHeightLimit = minOf(searchHeightLimit, availableHeight)
                }
                LaunchedEffect(viewportIdentity) {
                    if (previousViewport != viewportIdentity) {
                        searchHeightLimit = Int.MAX_VALUE
                        previousViewport = viewportIdentity
                    }
                }
                val widthLimit = minOf(availableWidth - margin * 2, with(density) {
                    when (placement) { GlassPlacement.Menu -> if (fitContent) availableWidth.toFloat() else 400.dp.toPx(); GlassPlacement.Center, GlassPlacement.Sheet -> 400.dp.toPx()
                        GlassPlacement.Search, GlassPlacement.Page -> availableWidth.toFloat() }
                }).roundToInt().coerceAtLeast(1)
                var measuredWidth by remember { mutableIntStateOf(-1) }
                val targetWidth = measuredWidth.takeIf { it > 0 }?.toFloat() ?: widthLimit.toFloat()
                val maxHeight = ((if (placement == GlassPlacement.Search) minOf(availableHeight, if (imeHeight > 0 || childPanels.intValue > 0) searchHeightLimit
                        else (searchHeightLimit.toLong() * 120 / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    else availableHeight) - margin * 2).roundToInt().coerceAtLeast(1)
                // Closing follows the restored trigger. Opening keeps the pre-collapse location.
                val livePreparedOrigin = continuousOrigin != null
                fun start() = (if (visible && !livePreparedOrigin) origin else returningHome ?: anchor.presentedBounds).translate(-windowOrigin)
                val detached = page || (origin == Rect.Zero && anchor.bounds == Rect.Zero)
                fun startWidth() = start().width.takeIf { it > 0f } ?: targetWidth.toFloat()
                fun startHeight() = start().height.takeIf { it > 0f } ?: with(density) { CompactControlSize.toPx() }
                fun homeRadius() = anchor.cornerRadius?.let { it * anchor.presentedScale } ?: with(density) { (startHeight() / 2).toDp() }
                fun radius() = if (page) PageCornerRadius else if (detached) PopupCornerRadius else popupCornerRadius(homeRadius(), PopupCornerRadius, geometry())
                // The body morphs from the very record its source surface draws; a geometry-only or quiet source is zero at home.
                val sourceMaterial = anchor.material.takeUnless { anchor.quiet || detached }
                val quietOptics = anchor.quiet || sourceMaterial == null && !detached
                fun bodyMaterial(): GlassSurfacePresentation {
                    val target = GlassSurfacePresentation(1f, 1f, popupLightMix, Color.Transparent, anchor.depthScale)
                    val source = sourceMaterial?.value ?: return target.copy(frost = phase(), refraction = if (quietOptics) motion.quietStrength.at(phase()) else 1f)
                    return source.towards(target, motion.expansion.boundedProgress(motion.geometryCurve))
                }
                val foregroundTargetHeight = if (viewportSized) viewportTargetHeight else targetHeight
                SideEffect { if (hasViewport) anchor.contact.vertical = kotlin.math.abs(foregroundTargetHeight - startHeight()) > kotlin.math.abs(targetWidth - startWidth()) }
                val dark = panelDark
                // Parent pixels invalidate the recording; they must not cancel its in-flight
                // thumbnail readback. Restart only when the sampling owner changes.
                val material = rememberFrostedBackdrop(listOf(samplingSource, visible, LocalGlassAppearance.current), dark = dark, lightMix = popupLightMix)
                SideEffect { material.parent = samplingSource; material.materialOnly = true }
                // This recording is sampled only by a later nested modal. Its
                // material already has a shared raster; don't allocate and redraw
                // another full-panel texture throughout every opening/closing.
                val completePanel = rememberFrostedBackdrop(window, dark = dark, lightMix = popupLightMix, retainRaster = false)
                SideEffect { completePanel.parent = samplingSource }
                // An empty search owns the keyboard: its target keeps the keyboard's reported
                // destination until the keyboard actually descends. A query rests on the final viewport.
                val openingSearchTarget = visible && searchDismissOnImeBack
                // IME-owned Back holds only an open resting window with its own placed keyboard.
                fun holdImeBack() = placement == GlassPlacement.Search && searchDismissOnImeBack && popupFlight.expanded &&
                    motion.openAtRest && windowFocused && childPanels.intValue == 0 &&
                    imeTarget == 0 && searchMotion.lastIme > 0
                fun surfaceOpacity() = if (!admittedDraw) 0f else if (detached) motion.detachedOpacity.at(phase()) else
                    if (popupFlight.present && window != null && popupFlight.window === window) 1f else 0f
                Box(Modifier.fillMaxSize().onGloballyPositioned {
                    if (hasViewport) windowOrigin = it.backdropPosition(view)
                }) {
                    // Measure and placement share one edge-rounded rectangle. Keep the
                    // shadow's layer-free placement on legacy Android.
                    val panelBounds = remember { arrayOfNulls<MotionPixelRect>(1) }
                    Layout(modifier = measureModifier.glassShadowOffset {
                        val pixels = panelBounds[0]
                        androidx.compose.ui.unit.IntOffset(pixels?.left ?: 0, pixels?.top ?: 0)
                    }.onGloballyPositioned {
                        if (!hasViewport || it.size.width == 0 || it.size.height == 0) return@onGloballyPositioned
                        if (it.size.width > 0 && it.size.height > 0 && window != null && modalWindow === window) measuredOriginWindow = window
                        workObserver?.invoke("geometry", window, phase())
                        if (placement == GlassPlacement.Search) {
                            val bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            val shown = motion.shown
                            val moving = motion.moving
                            searchMotion.placed(bounds, origin, phase(), shown, moving, imeHeight)
                            searchMotion.observer?.let { observer ->
                                val placed = motion.bounds?.value()
                                observer(SearchMorphSample(phase(), shown, bounds,
                                    searchMotion.flight, if (shown) searchMotion.target else anchor.presentedBounds,
                                    imeHeight, imeSource, imeTarget, placed?.bottom.takeIf { shown }, availableHeight.toFloat(),
                                    surfaceOpacity = surfaceOpacity(), nativeIme = nativeIme, windowFocused = windowFocused,
                                    slotFraction = popupFlight.searchSlot.fraction, slotTravel = popupFlight.searchSlot.travel,
                                    openingOwnedBounds = placed.takeIf { shown && moving }, slotBottom = placed?.bottom))
                            }
                        }
                    }.onPlaced { wellCoordinates[0] = it; windowPlane.layout=it }.drawWithContent {
                        if (!hasViewport) return@drawWithContent
                        material.materialCorner=radius().toPx()
                        // Match the trigger's draw-space deformation. A layout-layer
                        // scale changes global sampling coordinates, causing a small
                        // optical jump when the same body returns to its trigger.
                        val pose = if (continuousOrigin != null) anchor.originPresentation?.invoke(startHeight()) else null
                        val shape = if (page || reduced && pose == null) GlassShape() else pose?.shape ?: body.value
                        val vertical = pose?.vertical ?: anchor.contact.vertical
                        val scaledField=windowNode.fieldScalesWithBody()
                        val fieldSx=if(scaledField) shape.scaleX(vertical) else 1f
                        val fieldSy=if(scaledField) shape.scaleY(vertical) else 1f
                        val fieldShift=if(scaledField) pose?.horizontalShift ?: 0f else 0f
                        // Draw in this native window outside the sampled panel/backdrop.
                        // Read placed coordinates in the same draw, not a previous-frame
                        // globally-positioned state handoff. The original trigger restores
                        // before this decoration relinquishes its covered footprint.
                        val coordinates = wellCoordinates[0]
                        shadowReceiver[0] = null
                        if (wellStrength > 0f && !page && !detached && !anchor.contextual &&
                            popupFlight.expanded && admittedDraw &&
                            coordinates?.isAttached == true) {
                            val home = (returningHome ?: anchor.presentedBounds).translate(-coordinates.backdropPosition(view))
                            val caster = glassWellCaster(size, radius().toPx(), shape.scaleX(vertical),
                                shape.scaleY(vertical), pose?.horizontalShift ?: 0f)
                            with(originWell) { draw(home, homeRadius().toPx(), caster, wellStrength) }
                            shadowReceiver[0] = receiverField.receiver(home,homeRadius().toPx(),size,
                                fieldSx,fieldSy,fieldShift,
                                physicalSize = (returningHome ?: anchor.presentedBounds).size)
                        }
                        // The trigger's window keeps drawing its neighbors' shared shadow.
                        // At home this field must equal that union, not stack over it.
                        shadowExclusion[0] = null
                        if (!page && !detached && popupFlight.modalPresent && admittedDraw && coordinates?.isAttached == true) {
                            val surfaceOrigin = coordinates.backdropPosition(view)
                            val extent = glassShadowInset(shadowSpread).toPx()
                            val sx = shape.scaleX(vertical)
                            val sy = shape.scaleY(vertical)
                            val drawn = Rect(surfaceOrigin + Offset((pose?.horizontalShift ?: 0f) + size.width * (1f - sx) / 2f,
                                size.height * (1f - sy) / 2f), Size(size.width * sx, size.height * sy)).inflate(extent)
                            val neighbors = glassShadowNeighbors(anchor, this).filter { Rect(it.left, it.top, it.right, it.bottom).inflate(extent).overlaps(drawn) }
                            shadowExclusion[0] = nativeShadowExclusion(neighbors, surfaceOrigin, size,
                                fieldSx, fieldSy, fieldShift, weight = phase())
                        }
                        // Composite field and material once, with the original padded footprint.
                        nativeField.draw(this,surfaceOpacity(),wellStrength *
                            (if(anchor.quiet) motion.quietStrength.at(phase()) else if(detached) phase() else 1f),
                            radius().toPx(),shape.scaleX(vertical),shape.scaleY(vertical),pose?.horizontalShift ?: 0f,
                            shadowReceiver[0],shadowExclusion[0]) {
                            // R5 (#847): B holds every popup optic until the restored source takes it at its draw entry.
                            if (visible || popupFlight.expanded || anchor.restoredBodyReceipt != anchor.restoredDrawRequest)
                                popupFlight.refillBody(this@drawWithContent) {
                                    translate(left = pose?.horizontalShift ?: 0f) {
                                        scale(shape.scaleX(vertical), shape.scaleY(vertical)) {
                                            this@drawWithContent.drawContent()
                                            wellCoordinates[0]?.let { sharedContent?.drawNative(this, it, view) }
                                        }
                                    }
                                }
                            opticalBody.draw(this@drawWithContent)
                        }
                    }
                        .graphicsLayer { shape=RoundedCornerShape(radius()); clip=true }.glassPopupHome(childHome)
                        .recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        SurfaceLayer(Modifier.presentedAlpha {
                            if (quietOptics) motion.quietContent.at(phase()) else 1f
                        }.recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                LiquidGlassSurface(Modifier.fillMaxSize(), source = samplingSource,
                                    // Both optical inputs must exclude the inline modal; an ancestor
                                    // trigger source can now contain this very recording.
                                    homeSource = if (departingSource != null && homeSampleReady) samplingSource else homeSource, homeWeight = { motion.homeWeight.at(phase()) },
                                    homeBounds = { if (visible && !livePreparedOrigin) origin else returningHome ?: anchor.presentedBounds },
                                    homeFootprint = if (!basis.measured) null else ({
                                        if (returningInline) returningFootprint
                                        else if (visible && !livePreparedOrigin) basis.footprint
                                        else if (anchor.samplingLocation.coordinates?.isAttached == true) anchor.samplingFootprint()
                                        else anchor.lastSamplingFootprint
                                    }),
                                    pressFeedback = false, shadow = false,
                                    body = {
                                        if (continuousOrigin != null) anchor.originPresentation?.invoke(startHeight())?.shape ?: body.value
                                        else body.value
                                    },
                                    presentation = ::bodyMaterial, presentedRadius = ::radius, panelRim = ::phase)
                            }
                        }
                        SurfaceLayer(Modifier.presentedAlpha { if (page) 1f else motion.foreground.at(phase()) }
                            .onPlaced { sharedContent?.destination(it) }
                            .then(contentModifier)
                            .then(if (!motion.shown || !foregroundReady) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides lerp(Color(0xFF202124), Color.White, popupLightMix),
                                LocalModalBackdrop provides completePanel,
                                LocalChildPanels provides childPanels,
                                LocalPopupScrollEdges provides !page,
                                LocalSurfaceShadows provides null, LocalPageShadows provides null,
                                LocalShadowPlane provides windowPlane,
                                LocalGlassOrigin provides null,
                                LocalGlassPopupHome provides childHome,
                                LocalPopupDismiss provides dismissNow,
                                LocalGlassPanelReady provides (visible && admittedDraw && (placement == GlassPlacement.Search || panelReady))) {
                                GlassPanelTheme(popupLightMix, content)
                            }
                        }
                        SurfaceLayer(Modifier.presentedAlpha { motion.face.at(phase()) }
                            .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides (anchor.ink.takeIf { it != Color.Unspecified }
                                ?: if (dark) Color.White else Color(0xFF202124))) { anchor.face?.invoke() }
                        }
                    }) { children, panelConstraints ->
                        val measuredViewport = measure(panelConstraints, this, margin, maxHeight)
                        val viewportHeight = measuredViewport.viewportHeight
                        val contentMaxHeight = measuredViewport.contentMaxHeight
                        val fixedHeight = measuredViewport.fixedHeight
                        if (!hasViewport || measuredViewport.empty) {
                            // Do not measure/place the foreground at an invented 1px
                            // size or overwrite its remembered target. An empty
                            // measure retains all three composed children, including
                            // the draft owner; removing content here would not.
                            panelBounds[0] = null
                            return@Layout layout(panelConstraints.minWidth, panelConstraints.minHeight) { }
                        }
                        val contentWidth = if (fitContent)
                            children[1].maxIntrinsicWidth(contentMaxHeight).coerceIn(1, widthLimit) else widthLimit
                        measuredWidth = contentWidth
                        val foreground = children[1].measure(Constraints(minWidth = contentWidth, maxWidth = contentWidth,
                            minHeight = fixedHeight ?: if (page) contentMaxHeight else 0, maxHeight = fixedHeight ?: contentMaxHeight))
                        if (viewportSized) viewportTargetHeight = foreground.height else targetHeight = foreground.height
                        val placedHeight = foreground.height
                        val rightAligned = start().center.x > availableWidth / 2f
                        val x = if (placement == GlassPlacement.Menu)
                            (if (anchor.contextual) start().center.x - targetWidth / 2f else if (rightAligned) start().right - targetWidth else start().left).coerceIn(margin, (availableWidth - targetWidth - margin).coerceAtLeast(margin))
                            else (availableWidth - targetWidth) / 2f
                        val y = when (placement) {
                            GlassPlacement.Search -> if (imeHeight > 0)
                                (viewportHeight - placedHeight - margin).coerceAtLeast(margin)
                                else ((viewportHeight - placedHeight) / 2f).coerceAtLeast(margin)
                            GlassPlacement.Center -> (viewportHeight - placedHeight) / 2f
                            GlassPlacement.Sheet -> (viewportHeight - placedHeight - margin).coerceAtLeast(margin)
                            GlassPlacement.Page -> 0f
                            GlassPlacement.Menu -> (if (anchor.contextual) minOf(start().top,
                                (anchor.bottomLimit.takeIf { it.isFinite() }?.minus(windowOrigin.y) ?: (availableHeight - anchor.bottomClearance)) - placedHeight)
                                else if (start().center.y > viewportHeight / 2f) start().bottom - placedHeight else start().top)
                                .coerceIn(margin, (viewportHeight - placedHeight).toFloat().coerceAtLeast(margin))
                        }
                        val envelope = if (placement == GlassPlacement.Search) searchPanelTarget(windowOrigin,
                            availableWidth.toFloat(), viewportHeight.toFloat(), widthLimit.toFloat(), foreground.height.toFloat(),
                            margin, imeHeight, imeTarget, safeBottom, opening = openingSearchTarget)
                            else Rect(windowOrigin + Offset(x, y), Size(targetWidth, foreground.height.toFloat()))
                        sharedContent?.destinationOrigin(envelope.topLeft)
                        val home = if (detached) Rect(envelope.topLeft + Offset(if (page) with(density) { 28.dp.toPx() } else 0f,
                            if (page) 0f else margin * 2), envelope.size) else start().translate(windowOrigin)
                        // Every layout retargets the one shared rectangle (position and velocity kept). A nested
                        // action menu freezes its parent until it has returned; IME-owned Back holds one layout.
                        if (!holdImeBack() && (placement != GlassPlacement.Search || childPanels.intValue == 0)) {
                            motion.place(home, envelope, density.density, detached = detached)
                            if (placement == GlassPlacement.Search) {
                                searchMotion.target = envelope
                                popupFlight.searchSlot.bindBottom(requireNotNull(motion.bounds).bottom,
                                    if (motion.shown) envelope.bottom else home.bottom, if (motion.shown) 1f else 0f)
                            }
                        }
                        val pixels = glassMorphPixelBounds(requireNotNull(motion.bounds).value().translate(-windowOrigin))
                        panelBounds[0] = pixels
                        val width = pixels.width
                        val height = pixels.height
                        val backing = children[0].measure(Constraints.fixed(width, height))
                        val face = children[2].measure(Constraints.fixed(startWidth().roundToInt().coerceAtLeast(1), startHeight().roundToInt().coerceAtLeast(1)))
                        layout(width, height) {
                            backing.place(0, 0)
                            foreground.placeWithLayer((width - foreground.width) / 2, if (placement == GlassPlacement.Search) 0 else (height - foreground.height) / 2) {
                                // Reveal the whole fixed-layout menu, not a moving crop of its
                                // middle rows. A uniform scale keeps glyph proportions intact.
                                val fit = popupContentScale(width,height,foreground.width,foreground.height,page || placement == GlassPlacement.Search)
                                scaleX = fit; scaleY = fit
                            }
                            // A retained face may contain a Material Surface with a pointer node.
                            // Alpha and cleared semantics do not remove that invisible hit target.
                            // Keep measuring it for the return morph, but place it only while visible.
                            placePopupFace(face,(width - face.width) / 2,(height - face.height) / 2,::phase)
                        }
                    }
                }
            }
            }
            SliderCancelLayer(sliderCancel, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp)
        }
    } } }
}


/** One rectangle owns the popup's position and clip; never round position and size separately.
 * D23: a drawable extent keeps at least one pixel; the window clips a band overshoot. */
internal fun glassMorphPixelBounds(rect: Rect): MotionPixelRect =
    popupPixelBounds(rect)

/** Both actions press the same shell and keep their own click/drag ownership. */
@Composable
internal fun PopupActionPill(anchor: GlassAnchor, source: FrostedBackdrop? = LocalFrostedBackdrop.current,
    modifier: Modifier = Modifier,
    chromeVisibility: () -> Float = { 1f }, playerExpansion: io.github.anastaci1a.localica.ui.engine.ExpansionView? = null,
    leadingIcon: AppIcon = AppIcon.ActionSearch, trailingIcon: AppIcon = AppIcon.ActionEllipsis,
    leadingLabel: String = "Search library", trailingLabel: String = "More", tag: String = "utility-pill",
    role: TriggerRole<Nothing, Nothing>, menuVisible: Boolean = false, onMenuDismiss: () -> Unit = {},
    menuContent: (@Composable ColumnScope.() -> Unit)? = null,
    onSearch: () -> Unit, onMore: () -> Unit) {
    val raised = role == TriggerRole.Navigation
    val effectiveShadow: () -> Float = if (playerExpansion == null) chromeVisibility
        else ({ chromeVisibility() * (1f - playerExpansion.boundedProgress()) })
    val iconSize = if (raised) io.github.anastaci1a.localica.ui.spec.StockUiSpec.space.getValue(io.github.anastaci1a.localica.ui.spec.Space.Panel)
        else io.github.anastaci1a.localica.ui.spec.StockUiSpec.control.getValue(io.github.anastaci1a.localica.ui.spec.ControlSize.SettingsIcon)
    val face: @Composable () -> Unit = {
        io.github.anastaci1a.localica.ui.layout.ActionButtonFaceSlot(Modifier, iconOnly = false,
            divider = { GlassPillDivider() }, retainedFace = true, iconSize = iconSize,
            icon = { Icon(appIconPainter(leadingIcon), null, it) },
            label = { Icon(appIconPainter(trailingIcon), null, it) })
    }
    val surfaceModifier = modifier.glassAnchor(anchor, source, role = role, face = face).testTag(tag)
    LiquidGlassSurface(if (raised) surfaceModifier.pageGlassCaster(opacity = effectiveShadow) else surfaceModifier,
        source = source, shadow = !raised,
        contactAdmission = { raised || !anchor.hidden },
        depthScale = if (raised) RaisedGlassDepthScale else anchor.depthScale) {
        anchor.captureInk()
        io.github.anastaci1a.localica.ui.layout.ActionButtonFaceSlot(
            if (raised) Modifier.fillMaxSize() else Modifier.align(Alignment.Center), iconOnly = false,
            divider = { GlassPillDivider() },
            icon = { ActionIcon(leadingIcon, leadingLabel, glass = false, iconSize = iconSize,
                onClick = { anchor.reclaimOpening?.invoke(); onSearch() }) },
            label = { ActionIcon(trailingIcon, trailingLabel, glass = false, iconSize = iconSize,
                onClick = { anchor.reclaimOpening?.invoke(); onMore() }) })
    }
    if (menuContent != null) GlassMenu(menuVisible, anchor, onMenuDismiss, content = menuContent)
}
