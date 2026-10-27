package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
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

/** The trigger stays in layout while its one visible surface moves into a modal window. */
@Stable
internal class GlassAnchor {
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
    var triggerMounted = false
    var restoredDrawn = false
    var bounds by mutableStateOf(Rect.Zero)
    var hidden by mutableStateOf(false)
    var modalPresent by mutableStateOf(false)
    var shadowInModal by mutableStateOf(false)
    var source: FrostedBackdrop? = null
    var face by mutableStateOf<(@Composable () -> Unit)?>(null)
    var depthScale = RaisedGlassDepthScale
    var cornerRadius: androidx.compose.ui.unit.Dp? = null
    var quiet = false
    var contextual = false
    var bottomClearance = 0f
    var bottomLimit = Float.NaN
    var ink by mutableStateOf(Color.Unspecified)
}

@Composable
internal fun rememberGlassAnchor(): GlassAnchor {
    val ink = LocalContentColor.current
    return remember { GlassAnchor().also { it.ink = ink } }
}

/** Context menus return to the actual artwork shape, never an invented menu button. */
@Composable internal fun Modifier.contextMenuAnchor(anchor: GlassAnchor, circular: Boolean = false): Modifier {
    anchor.captureInk()
    val view = LocalView.current
    val source = LocalFrostedBackdrop.current
    val density = LocalDensity.current
    val clearance = with(density) { LocalBrowseObscuredInset.current.toPx() }
    SideEffect { anchor.source = source; anchor.quiet = true; anchor.contextual = true; anchor.bottomClearance = clearance; anchor.face = null }
    return backdropLocation(anchor.samplingLocation).onGloballyPositioned {
        anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        anchor.bottomLimit = location[1] + view.height - clearance
        anchor.cornerRadius = if (circular) with(density) { (it.size.width / 2f).toDp() } else 16.dp
        anchor.lastSamplingFootprint = anchor.samplingFootprint()
    }
}

@Composable
internal fun GlassAnchor.captureInk() {
    val color = LocalContentColor.current
    SideEffect { ink = color }
}

@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor): Modifier {
    val view = LocalView.current
    DisposableEffect(anchor) { anchor.triggerMounted = true; onDispose { anchor.triggerMounted = false } }
    return then(GlassDepthMarker(anchor)).backdropLocation(anchor.samplingLocation).onGloballyPositioned {
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
        .drawWithContent { if (!anchor.hidden) { drawContent(); anchor.restoredDrawn = true } }
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
/** Test-only opt-in for foreground child shadows; no ordinary caller provides a value. */
internal val LocalPopupContentShadowStrength = compositionLocalOf<Float?> { null }
/** Diagnostic-only outer finish: 0 ordinary, 1 no rim, 2 no frost coating. */
internal val LocalPopupFinishDiagnostic = compositionLocalOf { 0 }
/** Scalar-only diagnostics after recording the material supplied to popup children. */
internal data class PopupDerivedMaterialDraw(val uptime: Long, val viewIdentity: Int, val sourceIdentity: Int,
    val layerIdentity: Int, val parentIdentity: Int?, val parentLayerIdentity: Int?,
    val recorded: Boolean, val released: Boolean, val revision: Int, val requestedReadback: Int,
    val publishedReadback: Int, val snapshotIdentity: Int?, val fallbackIdentity: Int?,
    val lensIdentity: Int?, val finishMode: Int)
internal val LocalPopupDerivedMaterialObserver = compositionLocalOf<((PopupDerivedMaterialDraw) -> Unit)?> { null }
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
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).controlVerticalScroll(rememberScrollState()), content = content)
    }
}

/** Keep this call composed when closed so the same surface can return to its trigger. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    placement: GlassPlacement = GlassPlacement.Menu, darkOverride: Boolean? = null,
    fitContent: Boolean = placement == GlassPlacement.Menu,
    originPreparation: GlassOriginPreparation? = null, searchDismissOnImeBack: Boolean = false,
    content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val progress = anchor.contact.expansion
    val searchObserver = LocalSearchMorphObserver.current
    val body = rememberGlassBody({ anchor.contact.travel + progress.value },
        contact = { if (anchor.contact.pressed) 1f else 0f }, state = anchor.contact.state,
        active = { anchor.contact.modal })
    var present by remember { mutableStateOf(false) }
    var readyWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var originFootprint by remember { mutableStateOf<HomeBackdropFootprint?>(null) }
    var measuredHome by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    var modalWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var dismissalAttempt by remember { mutableIntStateOf(0) }
    val gestures = rememberModalGestureScope()
    DisposableEffect(gestures, visible, dismissalAttempt) {
        if (visible) gestures.activate() else gestures.deactivate()
        onDispose { gestures.deactivate() }
    }
    val searchMotion = remember { SearchMorphMotion() }
    SideEffect { searchMotion.observer = searchObserver }
    var searchFlight by remember { mutableStateOf<SearchMorphFlight?>(null) }
    val searchOpeningShape = remember { Animatable(Rect.Zero, SearchRectConverter) }
    // One rectangle owns opening expansion and travel, including retargets.
    val searchPlacement = remember { Animatable(Rect.Zero, SearchRectConverter) }
    var searchTarget by remember { mutableStateOf(Rect.Zero) }
    var searchFocusRequest by remember { mutableStateOf<Boolean?>(null) }
    var searchOpeningGeneration by remember { mutableIntStateOf(0) }
    var searchShapeStarted by remember { mutableStateOf(false) }
    var searchOpeningPositioned by remember { mutableStateOf(false) }

    var searchSlotToken by remember { mutableLongStateOf(0L) }
    var searchSlotTravel by remember { mutableStateOf<SearchSlotTravel?>(null) }
    var searchSlotReturnFrom by remember { mutableFloatStateOf(0f) }
    fun beginSearchReturn() {
        if (placement == GlassPlacement.Search && searchFlight?.opening != false) {
            val from = searchMotion.rendered.takeIf { it.width > 0f } ?: origin
            searchFlight = SearchMorphFlight(from, progress.value.coerceIn(0f, 1f), opening = false)
            val returning = requireNotNull(searchFlight)
            val token = anchor.searchSlot.begin()
            searchSlotToken = token
            val fromFraction = anchor.searchSlot.fraction
            searchSlotTravel = null
            searchSlotReturnFrom = fromFraction
            anchor.searchSlot.bind(token) { fromFraction * (1f - returning.fraction(progress.value)) }
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
    androidx.activity.compose.BackHandler(visible && originPreparation != null && !present) { dismissNow() }
    DisposableEffect(originPreparation) { onDispose { originPreparation?.reset() } }
    val transitionJob = remember { arrayOfNulls<Job>(1) }
    val reclaimOpening = remember(anchor) { {
        // A click updates its conditional caller before the next composition can
        // remount the retained entry. Stop the old return at that admission point,
        // otherwise its restored-draw acknowledgment can dispose this same window.
        if (!anchor.hidden && anchor.modalPresent && modalWindow != null && modalWindow === readyWindow) {
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
    LaunchedEffect(visible, reduced, speed, originPreparation) {
        transitionJob[0] = currentCoroutineContext().job
        if (!visible && !present) {
            anchor.hidden = false
            originPreparation?.settle(false, reduced, speed)
            return@LaunchedEffect
        }
        if (placement == GlassPlacement.Search) {
            if (visible) {
                searchSlotToken = anchor.searchSlot.begin()
                searchSlotTravel = null
                searchOpeningGeneration++
                searchFocusRequest = null
                searchShapeStarted = false
                searchOpeningPositioned = false
                val from = searchMotion.rendered.takeIf { present && it.width > 0f } ?: anchor.bounds
                searchFlight = SearchMorphFlight(from, progress.value.coerceIn(0f, 1f), opening = true)
            } else {
                beginSearchReturn()
                searchFocusRequest = null
            }
        }
        if (visible) {
            val retainedWindow = modalWindow?.takeIf { it === readyWindow }
            if (retainedWindow != null) {
                // Logical return can finish before Compose disposes this window.
                // Reclaim its prepared surface before suspending or exposing the trigger.
                present = true; anchor.modalPresent = true; anchor.contact.modal = true
                anchor.hidden = true; anchor.shadowInModal = true
                source?.refresh()
                originPreparation?.settle(true, reduced, speed)
                originFootprint = anchor.samplingFootprint()
                measuredHome = anchor.samplingLocation.coordinates != null
            } else {
                originPreparation?.settle(true, reduced, speed)
                // Let the prepared origin reach final placement before capture.
                if (originPreparation != null) withFrameNanos { }
                if (!present && anchor.bounds == Rect.Zero) withFrameNanos { }
                if (!present) {
                    origin = anchor.bounds; source = ambientSource ?: anchor.source
                }
                // Reopening an existing unready window is a new sampling admission too.
                originFootprint = anchor.samplingFootprint()
                measuredHome = anchor.samplingLocation.coordinates != null
                source?.refresh()
                present = true; anchor.modalPresent = true; anchor.contact.modal = true
                // A new or interrupted unready window still needs measured frames.
                // Never lend its readiness to a replacement native window.
                while (true) {
                    val preparingWindow = snapshotFlow { modalWindow }.filterNotNull().first()
                    withFrameNanos { }; withFrameNanos { }
                    if (modalWindow === preparingWindow) {
                        readyWindow = preparingWindow
                        anchor.hidden = true; anchor.shadowInModal = true
                        break
                    }
                }
            }
        }
        if (visible && placement == GlassPlacement.Search) {
            // Measurement is needed for shape growth, but keyboard preparation is not.
            // Expand and travel from the pill together; unknown IME targets use the safe bottom.
            snapshotFlow { searchTarget }.first { it.width > 0f && it.height > 0f }
            searchOpeningShape.snapTo(requireNotNull(searchFlight).from)
            searchShapeStarted = true
        }
        if (reduced) progress.snapTo(if (visible) 1f else 0f)
        else progress.animateTo(if (visible) 1f else 0f,
            glassMorphSpring(speed))
        if (visible) {
            // Hand off the identical endpoint before normal results/IME geometry
            // resumes. Late geometry is eased from here, never exposed as a snap.
            if (placement == GlassPlacement.Search) {
                // Transfer the same rectangle only after its single owner settles.
                snapshotFlow { searchFlight?.opening == true && searchOpeningPositioned &&
                    !searchOpeningShape.isRunning && searchOpeningShape.value == searchTarget }.first { it }
                searchPlacement.snapTo(searchTarget)
            }
            searchFlight = null
        }
        if (!visible) {
            if (placement == GlassPlacement.Search) anchor.searchSlot.settle(searchSlotToken, 0f)
            // The underlying window must draw its restored trigger before the modal
            // disappears. Keep the identical returned surface across that handoff.
            anchor.restoredDrawn = false
            anchor.hidden = false
            // The same body continues rebounding in the trigger. Wait for actual
            // drawing acknowledgement, keeping the modal material visible meanwhile.
            var frames = 0
            while (anchor.triggerMounted && !anchor.restoredDrawn && frames++ < 6) withFrameNanos { }
            present = false; anchor.modalPresent = false; anchor.shadowInModal = false
            anchor.contact.modal = false
            originPreparation?.settle(false, reduced, speed)
            searchFlight = null
            searchMotion.rendered = Rect.Zero
            searchMotion.lastIme = 0
        }
    }
    DisposableEffect(anchor) { onDispose {
        if (placement == GlassPlacement.Search) { val token = anchor.searchSlot.begin(); anchor.searchSlot.settle(token, 0f) }
        anchor.hidden = false; anchor.modalPresent = false; anchor.shadowInModal = false; anchor.contact.modal = false
    } }
    if (!present) return
    // The full-scene recording can span pages with different ink. Its material
    // metadata must not override an explicit trigger. Detached menus instead
    // follow current ambient ink, rather than their anchor's remembered seed.
    val panelInk = anchor.ink.takeIf { anchor.bounds != Rect.Zero && it != Color.Unspecified }
        ?: LocalContentColor.current
    val panelDark = darkOverride ?: if (panelInk != Color.Unspecified) panelInk.luminance() > .5f else source?.dark == true
    val parentPanels = LocalChildPanels.current
    val childPanels = remember { mutableIntStateOf(0) }
    DisposableEffect(parentPanels) {
        parentPanels?.let { it.intValue++ }
        onDispose { parentPanels?.let { it.intValue-- } }
    }
    Dialog(onDismissRequest = dismissNow, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { ModalGestureWindow(gestures) { TextEditingFocusHost {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        if (placement == GlassPlacement.Search) PreserveSearchImeState(window)
        val reportSearchOpening = remember(window, searchOpeningGeneration) {
            val generation = searchOpeningGeneration
            { requested: Boolean ->
                if (modalWindow === window && searchOpeningGeneration == generation) searchFocusRequest = requested
            }
        }
        DisposableEffect(window) {
            modalWindow = window
            if (placement == GlassPlacement.Search) searchSlotToken = anchor.searchSlot.renew()
            searchFocusRequest = null
            // We draw our own animated scrim. A zero-alpha native dim layer still
            // blocks touches as an untrusted occluder during return on Android 16.
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            onDispose {
                if (readyWindow === window) readyWindow = null
                if (modalWindow === window) {
                    if (placement == GlassPlacement.Search) searchSlotToken = anchor.searchSlot.renew()
                    modalWindow = null
                    searchFocusRequest = null
                }
            }
        }
        DisposableEffect(window, visible, dismissalAttempt) {
            // A busy editor may refuse dismissal; Settings Back may only change
            // the page. Reconcile with the actual state after that callback.
            window?.glassInput(visible)
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
        val p = progress.value.coerceIn(0f, 1f)
        val geometry = glassMorphGeometryProgress(p, anchor.quiet)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f * p))
            .pointerInput(visible) { detectTapGestures { if (visible) dismissNow() } }) {
            BoxWithConstraints(Modifier.fillMaxSize().then(if (placement == GlassPlacement.Page) Modifier else Modifier.safeDrawingPadding().imePadding())) {
                val density = LocalDensity.current
                var windowOrigin by remember { mutableStateOf(Offset.Zero) }
                var targetHeight by remember { mutableIntStateOf(0) }
                val expandedHeight = remember { Animatable(-1f) }
                LaunchedEffect(targetHeight, reduced, speed) {
                    if (targetHeight > 0) {
                        if (expandedHeight.value < 0f || reduced) expandedHeight.snapTo(targetHeight.toFloat())
                        else expandedHeight.animateTo(targetHeight.toFloat(), glassMorphSpring(speed))
                    }
                }
                val page = placement == GlassPlacement.Page
                val margin = with(density) { if (page) 0f else 12.dp.toPx() }
                val availableWidth = constraints.maxWidth
                val availableHeight = constraints.maxHeight
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val viewportIdentity = Triple(configuration.screenWidthDp, configuration.screenHeightDp, density.fontScale)
                var previousViewport by remember { mutableStateOf(viewportIdentity) }
                // Keep the keyboard-open viewport after IME dismissal. A nested
                // action menu freezes its parent's position until it has returned.
                val imeHeight = WindowInsets.ime.getBottom(density)
                val imeSource = WindowInsets.imeAnimationSource.getBottom(density)
                val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
                val safeBottom = WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(density)
                val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                val nativeIme = ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                SideEffect {
                    if (placement == GlassPlacement.Search && imeHeight > 0)
                        searchHeightLimit = minOf(searchHeightLimit, availableHeight)
                }
                LaunchedEffect(viewportIdentity) {
                    if (previousViewport != viewportIdentity) {
                        searchHeightLimit = Int.MAX_VALUE
                        previousViewport = viewportIdentity
                    }
                }
                val imeSettled = imeHeight == imeTarget && imeSource == imeTarget
                LaunchedEffect(placement, visible, searchTarget, searchFlight, searchShapeStarted, window,
                    childPanels.intValue, reduced, speed) {
                    if (placement == GlassPlacement.Search && visible && searchFlight?.opening == true &&
                        searchShapeStarted && childPanels.intValue == 0 && searchTarget.height > 0f) {
                        if (modalWindow !== window) return@LaunchedEffect
                        searchOpeningPositioned = true
                        val token = searchSlotToken
                        val travel = SearchSlotTravel(anchor.searchSlot.fraction, searchOpeningShape.value.bottom, searchTarget.bottom)
                        searchSlotTravel = travel
                        anchor.searchSlot.bind(token) { travel.fraction(searchOpeningShape.value.bottom) }
                        // Retarget from the rendered rectangle with its existing velocity.
                        // No native-IME delay or second placement animation splits the motion.
                        if (reduced) searchOpeningShape.snapTo(searchTarget)
                        else searchOpeningShape.animateTo(searchTarget, searchPlacementSpring(speed))
                        if (modalWindow === window) anchor.searchSlot.settle(token, 1f)
                    }
                }
                LaunchedEffect(placement, visible, searchTarget, searchFlight, imeHeight, imeSettled, childPanels.intValue, reduced, speed) {
                    if (placement == GlassPlacement.Search && visible && searchFlight == null &&
                        (imeSettled || imeHeight > 0 && imeTarget > 0) &&
                        childPanels.intValue == 0 && searchTarget.height > 0f) {
                        if (reduced) searchPlacement.snapTo(searchTarget)
                        else searchPlacement.animateTo(searchTarget, searchPlacementSpring(speed))
                    }
                }
                val widthLimit = minOf(availableWidth - margin * 2, with(density) {
                    when (placement) { GlassPlacement.Menu -> if (fitContent) availableWidth.toFloat() else 400.dp.toPx(); GlassPlacement.Center, GlassPlacement.Sheet -> 400.dp.toPx()
                        GlassPlacement.Search, GlassPlacement.Page -> availableWidth.toFloat() }
                }).roundToInt().coerceAtLeast(1)
                var measuredWidth by remember { mutableIntStateOf(-1) }
                val expandedWidth = remember { Animatable(-1f) }
                LaunchedEffect(measuredWidth, reduced, speed) {
                    if (measuredWidth <= 0) return@LaunchedEffect
                    if (expandedWidth.value < 0f || reduced) expandedWidth.snapTo(measuredWidth.toFloat())
                    else expandedWidth.animateTo(measuredWidth.toFloat(), glassMorphSpring(speed))
                }
                val targetWidth = (expandedWidth.value.takeIf { it > 0f } ?: measuredWidth.takeIf { it > 0 }?.toFloat() ?: widthLimit.toFloat()).roundToInt()
                val maxHeight = ((if (placement == GlassPlacement.Search) minOf(availableHeight, if (imeHeight > 0 || childPanels.intValue > 0) searchHeightLimit
                        else (searchHeightLimit.toLong() * 120 / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    else availableHeight) - margin * 2).roundToInt().coerceAtLeast(1)
                // Closing follows the restored trigger. Opening keeps the pre-collapse location.
                val start = (if (visible) origin else anchor.bounds).translate(-windowOrigin)
                val detached = page || (origin == Rect.Zero && anchor.bounds == Rect.Zero)
                val startWidth = start.width.takeIf { it > 0f } ?: targetWidth.toFloat()
                val startHeight = start.height.takeIf { it > 0f } ?: with(density) { CompactControlSize.toPx() }
                val homeRadius = anchor.cornerRadius ?: with(density) { (startHeight / 2).toDp() }
                val radius = if (page) 0.dp else if (detached) 28.dp else homeRadius * (1f - geometry) + 28.dp * geometry
                SideEffect { anchor.contact.vertical = kotlin.math.abs(targetHeight - startHeight) > kotlin.math.abs(targetWidth - startWidth) }
                val dark = panelDark
                val material = rememberFrostedBackdrop(listOf(source?.fallback, p == 1f, LocalGlassAppearance.current), dark = dark)
                SideEffect { material.parent = source; material.materialOnly = true; material.materialCorner = with(density) { radius.toPx() } }
                // This recording is sampled only by a later nested modal. Its
                // material already has a shared raster; don't allocate and redraw
                // another full-panel texture throughout every opening/closing.
                val finishMode = LocalPopupFinishDiagnostic.current
                val derivedObserver = LocalPopupDerivedMaterialObserver.current
                val completePanel = rememberFrostedBackdrop(p == 1f, dark = dark, retainRaster = false)
                SideEffect { completePanel.parent = source }
                fun mix(a: Float, b: Float) = a + (b - a) * geometry
                // Read readiness in composition even for committed queries, whose
                // IME-dismissal branch below is disabled. A SideEffect-only read
                // would leave a recreated popup at its initial bottom target.
                val openingSearchTarget = visible &&
                    (searchFlight?.opening == true || searchFocusRequest != false)
                SideEffect {
                    if (placement == GlassPlacement.Search && targetHeight > 0) {
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
                val flight = if (placement != GlassPlacement.Search) null
                    else if (!visible && searchFlight?.opening != false)
                        SearchMorphFlight(searchMotion.rendered.takeIf { it.width > 0f } ?: origin, p, opening = false)
                    else if (visible && searchFlight?.opening == false)
                        SearchMorphFlight(searchMotion.rendered.takeIf { it.width > 0f } ?: origin, p, opening = true)
                    else searchFlight
                // Native IME-owned Back updates its target before the editor's
                // effect runs. Hold that first layout at the pre-Back rectangle,
                // so the captured return cannot inherit one keyboard-following frame.
                val holdImeBack = placement == GlassPlacement.Search && searchDismissOnImeBack && visible && flight == null &&
                    searchFocusRequest == true && windowFocused && childPanels.intValue == 0 &&
                    imeTarget == 0 && (imeSource > 0 || searchMotion.lastIme > 0)
                // A visibility reversal can lay out once before its effect resets state.
                // The synthetic reopening flight must not borrow the old opening bottom.
                val openingBottom = searchOpeningShape.value.bottom.takeIf {
                    searchOpeningPositioned && visible && searchFlight?.opening == true
                }
                val slotFraction = if (placement == GlassPlacement.Search) anchor.searchSlot.fraction else 0f
                val slotTravel = searchSlotTravel
                val slotReturnFrom = searchSlotReturnFrom
                val flightRect = (flight?.let {
                    if (it.opening && visible) {
                        if (searchShapeStarted && searchFlight?.opening == true) searchOpeningShape.value else it.from
                    }
                    else it.rectangle(p, anchor.bounds)
                }
                    ?: searchMotion.rendered.takeIf { holdImeBack && it.width > 0f }
                    ?: searchPlacement.value.takeIf { placement == GlassPlacement.Search && it.width > 0f })?.translate(-windowOrigin)
                fun surfaceOpacity() = if (detached) p else
                    if (present && window != null && readyWindow === window) 1f else 0f
                Box(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.backdropPosition(view) }) {
                    Layout(modifier = Modifier.glassShadowOffset {
                        val rightAligned = start.center.x > availableWidth / 2f
                        val x = if (placement == GlassPlacement.Menu)
                            (if (anchor.contextual) start.center.x - targetWidth / 2f else if (rightAligned) start.right - targetWidth else start.left).coerceIn(margin, (availableWidth - targetWidth - margin).coerceAtLeast(margin))
                            else (availableWidth - targetWidth) / 2f
                        val y = when (placement) {
                            GlassPlacement.Search -> if (imeHeight > 0)
                                (availableHeight - targetHeight - margin).coerceAtLeast(margin)
                                else ((availableHeight - targetHeight) / 2f).coerceAtLeast(margin)
                            GlassPlacement.Center -> (availableHeight - targetHeight) / 2f
                            GlassPlacement.Sheet -> (availableHeight - targetHeight - margin).coerceAtLeast(margin)
                            GlassPlacement.Page -> 0f
                            GlassPlacement.Menu -> (if (anchor.contextual) minOf(start.top,
                                (anchor.bottomLimit.takeIf { it.isFinite() }?.minus(windowOrigin.y) ?: (availableHeight - anchor.bottomClearance)) - targetHeight)
                                else if (start.center.y > availableHeight / 2f) start.bottom - targetHeight else start.top)
                                .coerceIn(margin, (availableHeight - targetHeight).toFloat().coerceAtLeast(margin))
                        }
                        if (flightRect != null) androidx.compose.ui.unit.IntOffset(flightRect.left.roundToInt(), flightRect.top.roundToInt())
                        else if (page) androidx.compose.ui.unit.IntOffset(with(density) { (28.dp.toPx() * (1f - p)).roundToInt() }, 0)
                        else androidx.compose.ui.unit.IntOffset(mix(if (detached) x else start.left, x).roundToInt(),
                            mix(if (detached) y + margin * 2 else start.top, y).roundToInt())
                    }.onGloballyPositioned {
                        if (placement == GlassPlacement.Search) {
                            val bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            searchMotion.rendered = bounds
                            searchMotion.lastIme = imeHeight
                            searchMotion.observer?.invoke(SearchMorphSample(p, visible, bounds,
                                flight, if (visible) searchTarget else anchor.bounds,
                                imeHeight, imeSource, imeTarget, openingBottom, searchOpeningShape.value.takeIf { flight?.opening == true }, availableHeight.toFloat(),
                                surfaceOpacity = surfaceOpacity(), nativeIme = nativeIme, windowFocused = windowFocused,
                                slotFraction = slotFraction, slotTravel = slotTravel, slotReturnFrom = slotReturnFrom,
                                openingOwnedBounds = searchOpeningShape.value.takeIf { visible && searchShapeStarted && flight?.opening == true }))
                        }
                    }.drawWithContent {
                        // Match the trigger's draw-space deformation. A layout-layer
                        // scale changes global sampling coordinates, causing a small
                        // optical jump when the same body returns to its trigger.
                        val shape = if (reduced || page) GlassShape() else body.value
                        scale(shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical)) {
                            this@drawWithContent.drawContent()
                        }
                    }
                        .glassShadowOpacity {
                            // Keep this window's padded shadow in the same draw pass.
                            // A tight alpha graphics layer clips the moving field on
                            // legacy Android and changes it when the trigger takes over.
                            surfaceOpacity()
                        }
                        .clearGlassShadow(LocalGlassAppearance.current.shadowStrength *
                            (if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else if (detached) p else 1f),
                            LocalGlassAppearance.current.shadowSpread, radius)
                        .clip(RoundedCornerShape(radius)).recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.graphicsLayer {
                            if (anchor.quiet) { val t = (p / .25f).coerceIn(0f, 1f); alpha = t * t * (3f - 2f * t) }
                        }.then(if (derivedObserver == null) Modifier else Modifier.drawWithContent {
                            drawContent()
                            // This observes this material recording, not the pixels later consumed by a lens.
                            try { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                                derivedObserver(PopupDerivedMaterialDraw(android.os.SystemClock.uptimeMillis(), System.identityHashCode(view),
                                    System.identityHashCode(material), System.identityHashCode(material.layer),
                                    material.parent?.let(System::identityHashCode), material.parent?.layer?.let(System::identityHashCode),
                                    material.recorded, material.layer.isReleased, material.revision,
                                    material.readbackRequest, material.snapshotReadbackRequest,
                                    material.snapshot?.let(System::identityHashCode), material.fallback?.let(System::identityHashCode),
                                    material.lensSnapshot?.let(System::identityHashCode), finishMode))
                            } } catch (_: Throwable) { /* diagnostics must not affect drawing */ }
                        }).recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                LiquidGlassSurface(Modifier.fillMaxSize(), source = source,
                                    homeSource = anchor.source, homeWeight = { val home = (1f - p / .5f).coerceIn(0f, 1f); home * home * (3f - 2f * home) },
                                    homeBounds = { if (visible) origin else anchor.bounds },
                                    homeFootprint = if (!measuredHome) null else ({
                                        if (visible) originFootprint
                                        else if (anchor.samplingLocation.coordinates?.isAttached == true) anchor.samplingFootprint()
                                        else anchor.lastSamplingFootprint
                                    }),
                                    cornerRadius = radius, frostProgress = p, panelProgress = p, panelDark = dark,
                                    // Material and popup foreground share the resolved page polarity,
                                    // including explicit overrides and detached dialogs. The trigger's
                                    // original foreground is rendered separately during its return.
                                    inkOverride = if (dark) Color.White else Color(0xFF202124),
                                    pressFeedback = false, shadow = false,
                                    diagnosticFinishMode = finishMode,
                                    body = { body.value },
                                    depthScale = anchor.depthScale,
                                    refraction = if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else 1f)
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = if (page) 1f else ((p - .18f) / .65f).coerceIn(0f, 1f) }
                            .then(if (!visible || p < .85f) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides if (dark) Color.White else Color(0xFF202124),
                                LocalModalBackdrop provides completePanel,
                                LocalChildPanels provides childPanels,
                                LocalPopupScrollEdges provides !page,
                                LocalSurfaceShadows provides null, LocalBottomShadows provides null,
                                LocalGlassOrigin provides null,
                                LocalSearchOpeningReady provides reportSearchOpening.takeIf { placement == GlassPlacement.Search },
                                LocalSearchReturnStart provides (if (placement == GlassPlacement.Search) ::beginSearchReturn else null),
                                LocalGlassPanelReady provides (visible && (placement == GlassPlacement.Search || p >= .9f))) {
                                val childShadowStrength = LocalPopupContentShadowStrength.current
                                if (childShadowStrength == null) GlassPanelTheme(dark, content)
                                else CompositionLocalProvider(LocalGlassAppearance provides
                                    LocalGlassAppearance.current.copy(shadowStrength = childShadowStrength)) {
                                    GlassPanelTheme(dark, content)
                                }
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = (1f - p / .55f).coerceIn(0f, 1f) }
                            .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides (anchor.ink.takeIf { it != Color.Unspecified }
                                ?: if (dark) Color.White else Color(0xFF202124))) { anchor.face?.invoke() }
                        }
                    }) { children, _ ->
                        val contentWidth = if (fitContent)
                            children[1].maxIntrinsicWidth(maxHeight).coerceIn(1, widthLimit) else widthLimit
                        measuredWidth = contentWidth
                        val foreground = children[1].measure(Constraints(minWidth = contentWidth, maxWidth = contentWidth,
                            minHeight = if (page) maxHeight else 0, maxHeight = maxHeight))
                        targetHeight = foreground.height
                        val width = (if (flightRect != null) flightRect.width.roundToInt() else if (page) targetWidth else mix(if (detached) targetWidth * .96f else startWidth, targetWidth.toFloat()).roundToInt()).coerceIn(1, availableWidth)
                        val height = (if (flightRect != null) flightRect.height.roundToInt() else if (page) foreground.height else if (!detached && geometry < 0f)
                            (startHeight * (1f - geometry * 2f)).roundToInt()
                        else mix(if (detached) foreground.height * .96f else startHeight,
                            expandedHeight.value.takeIf { it >= 0f } ?: foreground.height.toFloat()).roundToInt()).coerceIn(1, availableHeight)
                        val backing = children[0].measure(Constraints.fixed(width, height))
                        val face = children[2].measure(Constraints.fixed(startWidth.roundToInt().coerceAtLeast(1), startHeight.roundToInt().coerceAtLeast(1)))
                        layout(width, height) {
                            backing.place(0, 0)
                            foreground.placeWithLayer((width - foreground.width) / 2, if (placement == GlassPlacement.Search) 0 else (height - foreground.height) / 2) {
                                // Reveal the whole fixed-layout menu, not a moving crop of its
                                // middle rows. A uniform scale keeps glyph proportions intact.
                                val fit = if (page || placement == GlassPlacement.Search) 1f else minOf(width.toFloat() / foreground.width.coerceAtLeast(1),
                                    height.toFloat() / foreground.height.coerceAtLeast(1)).coerceAtMost(1f)
                                scaleX = fit; scaleY = fit
                            }
                            // A retained face may contain a Material Surface with a pointer node.
                            // Alpha and cleared semantics do not remove that invisible hit target.
                            // Keep measuring it for the return morph, but place it only while visible.
                            if (p < .55f) face.place((width - face.width) / 2, (height - face.height) / 2)
                        }
                    }
                }
            }
        }
    } } }
}
