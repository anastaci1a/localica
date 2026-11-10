package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.PopupSharedContent

import io.github.anastaci1a.localica.ui.components.NoneReductionAtlasHost
import io.github.anastaci1a.localica.ui.layout.PopupViewportSlot
import io.github.anastaci1a.localica.ui.spec.PopupViewportInsets
import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import io.github.anastaci1a.localica.ui.engine.*
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
internal class GlassAnchor {
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
    val contact = GlassContact()
    val searchSlot = SearchSlotMotion()
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
    var reclaimOpening: (() -> Unit)? = null
    var triggerMounted by mutableStateOf(false)
    var restoredDrawn = false
    var restoredDrawRequest by mutableLongStateOf(0L)
    var restoredDrawReceipt = -1L
    var restoredPresentedReceipt by mutableLongStateOf(-1L)
    var bounds by mutableStateOf(Rect.Zero)
    var hidden by mutableStateOf(false)
    var modalPresent by mutableStateOf(false)
    var shadowInModal by mutableStateOf(false)
    // The shared shadow group its trigger casts into, if any.
    var shadowPlane: io.github.anastaci1a.localica.ui.engine.ShadowPlane? = null
    var source: FrostedBackdrop? = null
    var face by mutableStateOf<(@Composable () -> Unit)?>(null)
    var depthScale = RaisedGlassDepthScale
    var cornerRadius: androidx.compose.ui.unit.Dp? = null
    var quiet by mutableStateOf(false)
    var contextual = false
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

@Composable
internal fun rememberGlassAnchor(deferContextPlacement: Boolean = false): GlassAnchor {
    val ink = LocalContentColor.current
    val home = LocalGlassPopupHome.current
    return remember { GlassAnchor().also { it.ink = ink; it.contextPlacementTracked = !deferContextPlacement; it.popupHome = home } }
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
    SideEffect { anchor.popupHome = home; anchor.source = source; anchor.quiet = true; anchor.contextual = true; anchor.bottomClearance = clearance; anchor.face = null }
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
internal fun GlassAnchor.captureInk() {
    val color = LocalContentColor.current
    SideEffect { ink = color }
}

@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor): Modifier {
    val view = LocalView.current
    val home = LocalGlassPopupHome.current
    SideEffect { anchor.popupHome = home }
    DisposableEffect(anchor) { anchor.triggerMounted = true; onDispose { anchor.triggerMounted = false } }
    return then(if (anchor.quiet) Modifier.quietGlassCaster(anchor) else Modifier).then(GlassDepthMarker(anchor)).backdropLocation(anchor.samplingLocation).onGloballyPositioned {
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
                drawContent()
                anchor.restoredDrawn = true
                anchor.restoredDrawReceipt = restoreRequest
                view.post { if (anchor.restoredDrawReceipt == restoreRequest) anchor.restoredPresentedReceipt = restoreRequest }
            }
        }
        .then(if (anchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)
}

/** Carry the material profile with its trigger, including wrappers around shared buttons. */
private data class GlassDepthMarker(val anchor: GlassAnchor) : Modifier.Element
internal fun Modifier.glassSurfaceAnchor(): GlassAnchor? = foldIn<GlassAnchor?>(null) { anchor, element ->
    if (element is GlassDepthMarker) element.anchor else anchor
}
internal fun Modifier.glassDepthScale(): Float = foldIn(1f) { depth, element ->
    if (element is GlassDepthMarker) element.anchor.depthScale else depth
}

internal enum class GlassPlacement { Search, Menu, Center, Sheet, Page }
internal val LocalGlassPanelReady = compositionLocalOf { true }

/** Optional committed-scope observations; no progress subscription or behavior switch. */
internal val LocalGlassMorphWorkObserver =
    staticCompositionLocalOf<((String, android.view.Window?, Float?) -> Unit)?> { null }
// Only separate modal windows may read this scene. Inline lenses must keep using
// LocalFrostedBackdrop, whose recordings exclude their own foreground controls.
internal val LocalModalBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }
private val LocalChildPanels = staticCompositionLocalOf<MutableIntState?> { null }

private fun android.view.Window.glassInput(enabled: Boolean) {
    val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (attributes.flags and flags == if (enabled) 0 else flags) return
    if (enabled) clearFlags(flags) else addFlags(flags)
}

@Composable
internal fun GlassMenu(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    originPreparation: GlassOriginPreparation? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassMorphPopup(visible, anchor, onDismiss, if (anchor.bounds == Rect.Zero) GlassPlacement.Center else GlassPlacement.Menu,
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
    val retainedHeightSizing = remember(anchor, placement) { heightSizing }
    require(retainedHeightSizing == heightSizing) { "Height sizing is fixed for a popup presentation" }
    val popupHome = LocalGlassPopupHome.current
    val popupFlight = remember { GlassPopupFlight() }
    val requestedVisible = visible
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
    val progress = anchor.contact.expansion
    val continuousOrigin = originPreparation.takeUnless { placement == GlassPlacement.Search }
    val motion = sharedContent?.motion ?: rememberPopupWindowMotion(anchor, continuousOrigin != null, anchor.quiet)
    DisposableEffect(motion, continuousOrigin) {
        progress.bind(motion.expansion, motion.popupCurve)
        continuousOrigin?.bind(motion.expansion.phase, motion.preparationCurve)
        onDispose { progress.unbind(motion.expansion); continuousOrigin?.reset() }
    }
    var originAdmitted by remember { mutableStateOf(false) }
    var measuredOriginWindow by remember { mutableStateOf<android.view.Window?>(null) }
    val searchObserver = LocalSearchMorphObserver.current
    val body = rememberGlassBody({ anchor.contact.travel + progress.value },
        contact = { if (anchor.contact.pressed) 1f else 0f }, state = anchor.contact.state,
        active = { anchor.contact.modal })
    var present by remember { mutableStateOf(false) }
    var nativePlane by remember { mutableStateOf<ShadowPlane?>(null) }
    var nativeNode by remember { mutableStateOf<SurfaceView?>(null) }
    var leasedNode: SurfaceView? by remember { mutableStateOf(null) }
    val nativeLease = remember { arrayOfNulls<()->Unit>(1) }
    DisposableEffect(anchor) { onDispose { nativeLease[0]?.invoke(); nativeLease[0]=null } }
    var readyWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var originFootprint by remember { mutableStateOf<HomeBackdropFootprint?>(null) }
    var measuredHome by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    var modalWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var dismissalAttempt by remember { mutableIntStateOf(0) }
    val gestures = rememberModalGestureScope()
    val originOwnsInput = continuousOrigin == null || originAdmitted && anchor.hidden
    DisposableEffect(gestures, visible, dismissalAttempt, originOwnsInput) {
        if (visible && originOwnsInput) gestures.activate() else gestures.deactivate()
        onDispose { gestures.deactivate() }
    }
    val searchMotion = remember { SearchMorphMotion() }
    // Diagnostic intent origin precedes this composition's next placement callback.
    val searchOpeningFrom = remember(visible) { searchMotion.rendered.takeIf { it.width > 0f } }
    SideEffect { searchMotion.observer = searchObserver }
    var searchTransitioning by remember { mutableStateOf(false) }
    var searchReturning by remember { mutableStateOf(false) }
    SideEffect { if (visible || childHome.handedOff) motion.show(visible) }
    var searchFlight by remember { mutableStateOf<SearchMorphFlight?>(null) }
    var searchTarget by remember { mutableStateOf(Rect.Zero) }
    var searchFocusRequest by remember { mutableStateOf<Boolean?>(null) }
    var searchOpeningGeneration by remember { mutableIntStateOf(0) }
    var searchShapeStarted by remember { mutableStateOf(false) }
    var searchOpeningPositioned by remember { mutableStateOf(false) }

    var searchSlotToken by remember { mutableLongStateOf(0L) }
    var searchSlotTravel by remember { mutableStateOf<SearchSlotTravel?>(null) }
    var searchSlotReturnFrom by remember { mutableFloatStateOf(0f) }
    fun beginSearchReturn() {
        if (placement == GlassPlacement.Search && !searchReturning) {
            val from = searchMotion.rendered.takeIf { it.width > 0f } ?: origin
            searchReturning=true; searchTransitioning=true
            searchFlight = SearchMorphFlight(from, progress.value, opening = false)
            searchSlotToken = anchor.searchSlot.begin()
            searchSlotTravel = null
            searchSlotReturnFrom = anchor.searchSlot.fraction
            searchFocusRequest = null
        }
    }
    var searchHeightLimit by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    val dismissNow: () -> Unit = {
        // Release hit testing in the dismissal event itself. Waiting for the next
        // composition can swallow a quick tap on a neighboring trigger.
        beginSearchReturn()
        modalWindow?.glassInput(false)
        gestures.deactivate()
        onDismiss()
        dismissalAttempt++
    }
    val ambientSource = LocalModalBackdrop.current
    androidx.activity.compose.BackHandler(visible && originPreparation != null && (if (continuousOrigin != null) !originAdmitted || !anchor.hidden else !present)) { dismissNow() }
    val transitionJob = remember { arrayOfNulls<Job>(1) }
    val reclaimOpening = remember(anchor, continuousOrigin, placement.takeIf { continuousOrigin != null }) { {
        // A click updates its conditional caller before the next composition can
        // remount the retained entry. Stop the old return at that admission point,
        // otherwise its restored-draw acknowledgment can dispose this same window.
        if (continuousOrigin == null && !anchor.hidden && anchor.modalPresent && modalWindow != null && modalWindow === readyWindow) {
            sharedContent?.admitNative()
            transitionJob[0]?.cancel()
            transitionJob[0] = null
            present = true
            anchor.hidden = true
            anchor.shadowInModal = true
            anchor.contact.modal = true
        }
    } }
    DisposableEffect(anchor, reclaimOpening) {
        anchor.reclaimOpening = reclaimOpening
        onDispose { if (anchor.reclaimOpening === reclaimOpening) anchor.reclaimOpening = null }
    }
    // Native admission and return use real placement/draw receipts. Canceling this
    // adapter does not replace the expansion or its physical rectangle.
    LaunchedEffect(visible, reduced, speed, dismissalAttempt) {
        transitionJob[0] = currentCoroutineContext().job
        if (!visible && !present) return@LaunchedEffect
        if (visible && !present) {
            origin = anchor.presentedBounds
            source = ambientSource ?: anchor.source
            present = true
        }
        if (placement == GlassPlacement.Search) {
            if (visible) {
                searchSlotToken = anchor.searchSlot.begin()
                searchSlotTravel = null
                searchOpeningGeneration++
                searchReturning=false; searchTransitioning=true
                searchFocusRequest = null
                searchShapeStarted = false
                searchOpeningPositioned = false
                searchFlight = SearchMorphFlight(searchOpeningFrom ?: origin,
                    progress.value, opening = true)
            } else beginSearchReturn()
        }
        // Descendant native windows must enter this home before its opacity can move.
        if (!visible) snapshotFlow { childHome.handedOff }.first { it }
        motion.show(visible)
        snapshotFlow { motion.bounds != null }.first { it }
        while (true) {
            snapshotFlow { motion.expansion.awaitingNativeGate || motion.expansion.retirementReady }.first { it }
            if (motion.expansion.awaitingNativeGate) {
                val generation = motion.expansion.nativeGeneration
                if (motion.expansion.nativeOpening == true && visible) {
                    continuousOrigin?.beginOriginProof(generation)
                    snapshotFlow { modalWindow != null && measuredOriginWindow === modalWindow }.first { it }
                    continuousOrigin?.awaitDrawn()
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
                    originFootprint = anchor.samplingFootprint()
                    measuredHome = anchor.samplingLocation.coordinates?.isAttached == true
                    source?.refresh()
                    readyWindow = window
                    originAdmitted = true
                    anchor.modalPresent = true; anchor.contact.modal = true
                    sharedContent?.admitNative()
                    anchor.hidden = true; anchor.shadowInModal = true
                    // A closing gate can finish after visibility reversed; its
                    // input release must not outlive the new native admission.
                    if (continuousOrigin == null) {
                        window.glassInput(true); gestures.activate()
                    }
                } else if (motion.expansion.nativeOpening == false) {
                    fun closingGateCurrent() = motion.expansion.nativeGeneration == generation && motion.expansion.nativeOpening == false
                    modalWindow?.glassInput(false)
                    gestures.deactivate()
                    continuousOrigin?.beginOriginProof(generation)
                    anchor.restoredDrawn = false
                    val receipt = ++anchor.restoredDrawRequest
                    sharedContent?.restoreInstance()
                    anchor.hidden = false
                    snapshotFlow { !anchor.triggerMounted || anchor.restoredPresentedReceipt == receipt }.first { it }
                    continuousOrigin?.awaitDrawn { anchor.triggerMounted }
                    val node=leasedNode
                    if (node!=null) while (anchor.triggerMounted) {
                        if (!closingGateCurrent()) return@LaunchedEffect
                        val home=node.returnPlane() ?: break
                        node.transferWindow(home)
                        home.view?.invalidate()
                        snapshotFlow { !anchor.triggerMounted || node.ownsWindow(home) || node.returnPlane()!==home }.first { it }
                        if (!closingGateCurrent()) return@LaunchedEffect
                        if (node.ownsWindow(home) && node.awaitWindowField(home)) break
                    }
                    if (!closingGateCurrent()) return@LaunchedEffect
                    originAdmitted = false
                    sharedContent?.releaseNative()
                    anchor.modalPresent = false; anchor.contact.modal = false
                    anchor.shadowInModal = false
                }
                motion.expansion.acknowledgeNativeGate(generation)
            } else {
                if (!visible) {
                    if (placement == GlassPlacement.Search) anchor.searchSlot.settle(searchSlotToken, 0f)
                    nativeLease[0]?.invoke(); nativeLease[0]=null; leasedNode=null
                    sharedContent?.restoreInstance(); sharedContent?.releaseNative()
                    present = false; originAdmitted = false
                    anchor.hidden = false; anchor.modalPresent = false
                    anchor.shadowInModal = false; anchor.contact.modal = false
                    searchMotion.rendered = Rect.Zero; searchMotion.lastIme = 0
                } else if (placement == GlassPlacement.Search) anchor.searchSlot.settle(searchSlotToken, 1f)
                searchFlight = null; searchTransitioning=false
                break
            }
        }
    }
    DisposableEffect(anchor) { onDispose {
        if (placement == GlassPlacement.Search) { val token = anchor.searchSlot.begin(); anchor.searchSlot.settle(token, 0f) }
        anchor.hidden = false; anchor.modalPresent = false; anchor.shadowInModal = false; anchor.contact.modal = false
    } }
    if (!present || popupHome?.mounted == false) return
    val departingSource = popupHome?.backdrop.takeIf { returningInline || popupFlight.wasProjected }
    // Navigation keeps the native window in place until this scene is ready.
    // Ordinary popup input must not continuously capture the full-screen
    // backdrop: that readback can starve drag cleanup and fresh fling admission.
    BackdropLease(departingSource)
    val homeSampleReady = departingSource?.recorded == true &&
        (android.os.Build.VERSION.SDK_INT >= 33 || departingSource.fallback != null)
    val samplingSource = departingSource.takeIf { homeSampleReady } ?: source
    val flightLayer = rememberGraphicsLayer()
    DisposableEffect(popupHome, popupFlight, flightLayer) {
        popupFlight.layer = flightLayer
        popupHome?.flights?.add(popupFlight)
        onDispose {
            popupHome?.flights?.remove(popupFlight)
            popupFlight.layer = null; popupFlight.prepared = false; popupFlight.handedOff = false; popupFlight.wasProjected = false
        }
    }
    SideEffect { if (!returningInline) popupFlight.handedOff = false }
    // The full-scene recording can span pages with different ink. Its material
    // metadata must not override an explicit trigger. Detached menus instead
    // follow current ambient ink, rather than their anchor's remembered seed.
    val panelInk = anchor.ink.takeIf { anchor.bounds != Rect.Zero && it != Color.Unspecified }
        ?: LocalContentColor.current
    val panelDark = darkOverride ?: if (panelInk != Color.Unspecified) panelInk.luminance() > .5f else source?.dark == true
    val parentPanels = LocalChildPanels.current
    val childPanels = remember { mutableIntStateOf(0) }
    val countsAsPanel = continuousOrigin == null || originAdmitted
    DisposableEffect(parentPanels, countsAsPanel) {
        if (countsAsPanel) parentPanels?.let { it.intValue++ }
        onDispose { if (countsAsPanel) parentPanels?.let { it.intValue-- } }
    }
    Dialog(onDismissRequest = { if (continuousOrigin == null || originAdmitted) dismissNow() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { ModalGestureWindow(gestures) { TextEditingFocusHost { NoneReductionAtlasHost(GlassColorShader) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val windowPlane = rememberShadowPlane()
        val windowNode = anchor.surfaceNode ?: leasedNode ?: rememberWindowCaster(windowPlane,anchor)
        val nativeField=rememberNativeSurfaceField(windowPlane,windowNode,shadowSpread)
        SideEffect { nativePlane=windowPlane; nativeNode=windowNode }
        val workObserver = LocalGlassMorphWorkObserver.current
        if (workObserver != null) SideEffect { workObserver("dialog", window, null) }
        if (placement == GlassPlacement.Search) PreserveSearchImeState(window)
        val reportSearchOpening = remember(window, searchOpeningGeneration) {
            val generation = searchOpeningGeneration
            { requested: Boolean ->
                if (modalWindow === window && searchOpeningGeneration == generation) searchFocusRequest = requested
            }
        }
        DisposableEffect(window) {
            window?.let { motion.attached(it, windowPlane) }
            modalWindow = window
            if (continuousOrigin != null && (!originAdmitted || readyWindow !== window)) {
                window?.glassInput(false)
                if (originAdmitted) {
                    // A replacement window cannot borrow the old window's admission.
                    // Its old frozen surface is gone; restore the trigger and close.
                    originAdmitted = false
                    sharedContent?.restoreInstance(); sharedContent?.releaseNative()
                    anchor.hidden = false; anchor.shadowInModal = false
                    anchor.modalPresent = false; anchor.contact.modal = false
                    dismissNow()
                }
            }
            if (placement == GlassPlacement.Search) searchSlotToken = anchor.searchSlot.renew()
            searchFocusRequest = null
            // We draw our own animated scrim. A zero-alpha native dim layer still
            // blocks touches as an untrusted occluder during return on Android 16.
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            onDispose {
                if (measuredOriginWindow === window) measuredOriginWindow = null
                if (readyWindow === window) readyWindow = null
                if (nativePlane === windowPlane) {
                    sharedContent?.destination(null)
                    nativePlane=null; nativeNode=null
                }
                if (modalWindow === window) {
                    if (placement == GlassPlacement.Search) searchSlotToken = anchor.searchSlot.renew()
                    modalWindow = null
                    searchFocusRequest = null
                }
            }
        }
        DisposableEffect(window, visible, dismissalAttempt, originOwnsInput) {
            // A busy editor may refuse dismissal; Settings Back may only change
            // the page. Reconcile with the actual state after that callback.
            window?.glassInput(visible && (continuousOrigin == null || originAdmitted && anchor.hidden && readyWindow === window))
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
        val admittedDraw = continuousOrigin == null || originAdmitted && readyWindow === window
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
                        popupFlight.prepared = returningInline && homeSampleReady && samplingSource === popupHome.backdrop
                        if (!returningInline) popupFlight.handedOff = false
                    }
                    if (!returningInline || !popupFlight.handedOff) drawLayer(flightLayer)
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
                val margin = with(density) { if (page) 0f else 12.dp.toPx() }
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
                val imeSettled = imeHeight == imeTarget && imeSource == imeTarget
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
                fun radius() = if (page) 0.dp else if (detached) 28.dp else homeRadius() * (1f - geometry()) + 28.dp * geometry()
                val foregroundTargetHeight = if (viewportSized) viewportTargetHeight else targetHeight
                SideEffect { if (hasViewport) anchor.contact.vertical = kotlin.math.abs(foregroundTargetHeight - startHeight()) > kotlin.math.abs(targetWidth - startWidth()) }
                val dark = panelDark
                // Parent pixels invalidate the recording; they must not cancel its in-flight
                // thumbnail readback. Restart only when the sampling owner changes.
                val material = rememberFrostedBackdrop(listOf(samplingSource, visible, LocalGlassAppearance.current), dark = dark)
                SideEffect { material.parent = samplingSource; material.materialOnly = true }
                // This recording is sampled only by a later nested modal. Its
                // material already has a shared raster; don't allocate and redraw
                // another full-panel texture throughout every opening/closing.
                val completePanel = rememberFrostedBackdrop(window, dark = dark, retainRaster = false)
                SideEffect { completePanel.parent = samplingSource }
                // Read readiness in composition even for committed queries, whose
                // IME-dismissal branch below is disabled. A SideEffect-only read
                // would leave a recreated popup at its initial bottom target.
                val openingSearchTarget = visible &&
                    (searchTransitioning || searchFocusRequest != false)
                SideEffect {
                    if (hasViewport && placement == GlassPlacement.Search && targetHeight > 0) {
                        // Aim at the keyboard's reported destination immediately, rather
                        // than following each inset frame and waiting for native onEnd.
                        searchTarget = searchPanelTarget(windowOrigin, availableWidth.toFloat(), availableHeight.toFloat(),
                            widthLimit.toFloat(), targetHeight.toFloat(), margin, imeHeight, imeTarget, safeBottom,
                            opening = openingSearchTarget)
                    }
                }
                // A visibility change may precede the animation effect. Freeze the
                // last placed rectangle in that very layout, before IME descent can
                // alter it. The home anchor remains live as list vacancy is restored.
                fun flight() = searchFlight.takeIf { placement == GlassPlacement.Search && searchTransitioning }
                // Native IME-owned Back updates its target before the editor's
                // effect runs. Hold that first layout at the pre-Back rectangle,
                // so the captured return cannot inherit one keyboard-following frame.
                val holdImeBack = placement == GlassPlacement.Search && searchDismissOnImeBack && visible && !searchTransitioning &&
                    searchFocusRequest == true && windowFocused && childPanels.intValue == 0 &&
                    imeTarget == 0 && (imeSource > 0 || searchMotion.lastIme > 0)
                // A visibility reversal can lay out once before its effect resets state.
                // The synthetic reopening flight must not borrow the old opening bottom.
                val physical = motion.bounds
                fun surfaceOpacity() = if (!admittedDraw) 0f else if (detached) phase() else
                    if (present && window != null && readyWindow === window) 1f else 0f
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
                            val flight=flight()
                            val slotFraction=anchor.searchSlot.fraction
                            val slotTravel=searchSlotTravel
                            val slotReturnFrom=searchSlotReturnFrom
                            val slotBottom=motion.bounds?.bottom?.value()
                            val openingBottom=physical?.bottom?.value().takeIf { searchOpeningPositioned && visible && searchTransitioning }
                            val bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            searchMotion.rendered = bounds
                            searchMotion.lastIme = imeHeight
                            searchMotion.observer?.invoke(SearchMorphSample(phase(), visible, bounds,
                                flight, if (visible) searchTarget else anchor.presentedBounds,
                                imeHeight, imeSource, imeTarget, openingBottom, motion.bounds?.value().takeIf { visible && searchTransitioning }, availableHeight.toFloat(),
                                surfaceOpacity = surfaceOpacity(), nativeIme = nativeIme, windowFocused = windowFocused,
                                slotFraction = slotFraction, slotTravel = slotTravel, slotReturnFrom = slotReturnFrom,
                                openingOwnedBounds = motion.bounds?.value().takeIf { visible && searchShapeStarted && searchTransitioning },
                                slotBottom = slotBottom))
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
                        if (wellStrength > 0f && !page && !detached && !anchor.contextual && placement != GlassPlacement.Search &&
                            anchor.hidden && admittedDraw &&
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
                        if (!page && !detached && anchor.shadowInModal && admittedDraw && coordinates?.isAttached == true) {
                            val surfaceOrigin = coordinates.backdropPosition(view)
                            val extent = (4f + shadowSpread * 28f + 2f).dp.toPx()
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
                            translate(left = pose?.horizontalShift ?: 0f) {
                                scale(shape.scaleX(vertical), shape.scaleY(vertical)) {
                                    this@drawWithContent.drawContent()
                                    wellCoordinates[0]?.let { sharedContent?.drawNative(this, it, view) }
                                }
                            }
                        }
                    }
                        .graphicsLayer { shape=RoundedCornerShape(radius()); clip=true }.glassPopupHome(childHome)
                        .recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.graphicsLayer {
                            if (anchor.quiet) alpha = motion.quietContent.at(phase())
                        }.recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                LiquidGlassSurface(Modifier.fillMaxSize(), source = samplingSource,
                                    // Both optical inputs must exclude the inline modal; an ancestor
                                    // trigger source can now contain this very recording.
                                    homeSource = if (departingSource != null && homeSampleReady) samplingSource else anchor.source, homeWeight = { motion.homeWeight.at(phase()) },
                                    homeBounds = { if (visible && !livePreparedOrigin) origin else returningHome ?: anchor.presentedBounds },
                                    homeFootprint = if (!measuredHome) null else ({
                                        if (returningInline) returningFootprint
                                        else if (visible && !livePreparedOrigin) originFootprint
                                        else if (anchor.samplingLocation.coordinates?.isAttached == true) anchor.samplingFootprint()
                                        else anchor.lastSamplingFootprint
                                    }),
                                    panelDark = dark,
                                    // Material and popup foreground share the resolved page polarity,
                                    // including explicit overrides and detached dialogs. The trigger's
                                    // original foreground is rendered separately during its return.
                                    inkOverride = if (dark) Color.White else Color(0xFF202124),
                                    pressFeedback = false, shadow = false,
                                    body = {
                                        if (continuousOrigin != null) anchor.originPresentation?.invoke(startHeight())?.shape ?: body.value
                                        else body.value
                                    },
                                    depthScale = anchor.depthScale,
                                    presentation = {
                                        GlassSurfacePresentation(radius(), phase(), phase(), if (anchor.quiet) motion.quietStrength.at(phase()) else 1f)
                                    })
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = if (page) 1f else motion.foreground.at(phase()) }
                            .onPlaced { sharedContent?.destination(it) }
                            .then(contentModifier)
                            .then(if (!visible || !foregroundReady) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides if (dark) Color.White else Color(0xFF202124),
                                LocalModalBackdrop provides completePanel,
                                LocalChildPanels provides childPanels,
                                LocalPopupScrollEdges provides !page,
                                LocalSurfaceShadows provides null, LocalPageShadows provides null,
                                LocalShadowPlane provides windowPlane,
                                LocalGlassOrigin provides null,
                                LocalGlassPopupHome provides childHome,
                                LocalSearchOpeningReady provides reportSearchOpening.takeIf { placement == GlassPlacement.Search },
                                LocalSearchReturnStart provides (if (placement == GlassPlacement.Search) ::beginSearchReturn else null),
                                LocalGlassPanelReady provides (visible && admittedDraw && (placement == GlassPlacement.Search || panelReady))) {
                                GlassPanelTheme(dark, content)
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = motion.face.at(phase()) }
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
                        // A return intent can precede the outer visibility commit. Keep
                        // its captured opening envelope while that commit catches up.
                        if (!holdImeBack && !(placement == GlassPlacement.Search && visible && searchReturning) &&
                            (placement != GlassPlacement.Search || childPanels.intValue == 0 &&
                            (imeSettled || imeHeight > 0 && imeTarget > 0 || searchTransitioning))) {
                            motion.place(home, envelope, density.density)
                            if (placement == GlassPlacement.Search) {
                                if (visible) { searchShapeStarted=true; searchOpeningPositioned=true }
                                val rect=requireNotNull(motion.bounds)
                                val travel=anchor.searchSlot.bindBottom(searchSlotToken,rect.bottom,
                                    if(visible) envelope.bottom else home.bottom,if(visible) 1f else 0f)
                                searchSlotTravel=travel
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
    } } } }
}


/** One rectangle owns the popup's position and clip; never round position and size separately.
 * D23: a drawable extent keeps at least one pixel; the window clips a band overshoot. */
internal fun glassMorphPixelBounds(rect: Rect): MotionPixelRect =
    popupPixelBounds(rect)
