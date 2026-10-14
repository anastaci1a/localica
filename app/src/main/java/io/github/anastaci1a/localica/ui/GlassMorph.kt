package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToInt

/** The trigger stays in layout while its one visible surface moves into a modal window. */
@Stable
internal class GlassAnchor {
    val contact = GlassContact()
    var triggerMounted = false
    var restoredDrawn = false
    var bounds by mutableStateOf(Rect.Zero)
    var hidden by mutableStateOf(false)
    var modalPresent by mutableStateOf(false)
    var shadowInModal by mutableStateOf(false)
    var source: FrostedBackdrop? = null
    var face: (@Composable () -> Unit)? = null
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
    val view = LocalView.current
    val source = LocalFrostedBackdrop.current
    val density = LocalDensity.current
    val clearance = with(density) { LocalBrowseObscuredInset.current.toPx() }
    SideEffect { anchor.source = source; anchor.quiet = true; anchor.contextual = true; anchor.bottomClearance = clearance; anchor.face = null }
    return onGloballyPositioned {
        anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        anchor.bottomLimit = location[1] + view.height - clearance
        anchor.cornerRadius = if (circular) with(density) { (it.size.width / 2f).toDp() } else 16.dp
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
    return then(GlassDepthMarker(anchor)).onGloballyPositioned {
        anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat()))
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
internal fun GlassMenu(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassMorphPopup(visible, anchor, onDismiss, if (anchor.bounds == Rect.Zero) GlassPlacement.Center else GlassPlacement.Menu) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

/** Keep this call composed when closed so the same surface can return to its trigger. */
@Composable
internal fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    placement: GlassPlacement = GlassPlacement.Menu, darkOverride: Boolean? = null,
    fitContent: Boolean = placement == GlassPlacement.Menu, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val progress = anchor.contact.expansion
    val body = rememberGlassBody({ anchor.contact.travel + progress.value },
        contact = { if (anchor.contact.pressed) 1f else 0f }, state = anchor.contact.state,
        active = { anchor.contact.modal })
    var present by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    var modalWindow by remember { mutableStateOf<android.view.Window?>(null) }
    var dismissalAttempt by remember { mutableIntStateOf(0) }
    var searchHeightLimit by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    val dismissNow: () -> Unit = {
        // Release hit testing in the dismissal event itself. Waiting for the next
        // composition can swallow a quick tap on a neighboring trigger.
        modalWindow?.glassInput(false)
        onDismiss()
        dismissalAttempt++
    }
    val ambientSource = LocalModalBackdrop.current
    LaunchedEffect(visible, reduced, speed) {
        if (!visible && !present) { anchor.hidden = false; return@LaunchedEffect }
        if (visible) {
            if (!present && anchor.bounds == Rect.Zero) withFrameNanos { }
            if (!present) { origin = anchor.bounds; source = ambientSource ?: anchor.source }
            source?.refresh()
            present = true; anchor.modalPresent = true; anchor.contact.modal = true
            // Keep the origin visible until the modal has a measured surface.
            withFrameNanos { }; withFrameNanos { }
            anchor.hidden = true; surfaceReady = true; anchor.shadowInModal = true
        }
        if (reduced) progress.snapTo(if (visible) 1f else 0f)
        else progress.animateTo(if (visible) 1f else 0f,
            glassMorphSpring(speed))
        if (!visible) {
            // The underlying window must draw its restored trigger before the modal
            // disappears. Keep the identical returned surface across that handoff.
            anchor.restoredDrawn = false
            anchor.hidden = false
            // The same body continues rebounding in the trigger. Wait for actual
            // drawing acknowledgement, keeping the modal material visible meanwhile.
            var frames = 0
            while (anchor.triggerMounted && !anchor.restoredDrawn && frames++ < 6) withFrameNanos { }
            present = false; surfaceReady = false; anchor.modalPresent = false; anchor.shadowInModal = false
            anchor.contact.modal = false
        }
    }
    DisposableEffect(anchor) { onDispose { anchor.hidden = false; anchor.modalPresent = false; anchor.shadowInModal = false; anchor.contact.modal = false } }
    if (!present) return
    val parentPanels = LocalChildPanels.current
    val childPanels = remember { mutableIntStateOf(0) }
    DisposableEffect(parentPanels) {
        parentPanels?.let { it.intValue++ }
        onDispose { parentPanels?.let { it.intValue-- } }
    }
    Dialog(onDismissRequest = dismissNow, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { TextEditingFocusHost {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            modalWindow = window
            // We draw our own animated scrim. A zero-alpha native dim layer still
            // blocks touches as an untrusted occluder during return on Android 16.
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            onDispose { if (modalWindow === window) modalWindow = null }
        }
        DisposableEffect(window, visible, dismissalAttempt) {
            // A busy editor may refuse dismissal; Settings Back may only change
            // the page. Reconcile with the actual state after that callback.
            window?.glassInput(visible)
            onDispose { }
        }
        @Suppress("DEPRECATION")
        val windowDark = darkOverride ?: (source?.dark == true)
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
        val geometry = if (anchor.quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
        GlassShadowScope(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f * p))
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
                // Keep the keyboard-open viewport after IME dismissal. A nested
                // action menu freezes its parent's position until it has returned.
                val imeHeight = WindowInsets.ime.getBottom(density)
                SideEffect {
                    if (placement == GlassPlacement.Search && imeHeight > 0)
                        searchHeightLimit = minOf(searchHeightLimit, availableHeight)
                }
                val searchY = remember { Animatable(margin) }
                LaunchedEffect(placement, imeHeight, availableHeight, targetHeight, childPanels.intValue, reduced, speed) {
                    if (placement == GlassPlacement.Search && childPanels.intValue == 0) {
                        val destination = if (imeHeight > 0) margin else ((availableHeight - targetHeight) / 2f).coerceAtLeast(margin)
                        if (reduced) searchY.snapTo(destination)
                        else searchY.animateTo(destination, glassMorphSpring(speed))
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
                val maxHeight = ((if (placement == GlassPlacement.Search) minOf(availableHeight, searchHeightLimit)
                    else availableHeight) - margin * 2).roundToInt().coerceAtLeast(1)
                // Closing follows the restored trigger. Opening keeps the pre-collapse location.
                val start = (if (visible) origin else anchor.bounds).translate(-windowOrigin)
                val detached = page || (origin == Rect.Zero && anchor.bounds == Rect.Zero)
                val startWidth = start.width.takeIf { it > 0f } ?: targetWidth.toFloat()
                val startHeight = start.height.takeIf { it > 0f } ?: with(density) { CompactControlSize.toPx() }
                val homeRadius = anchor.cornerRadius ?: with(density) { (startHeight / 2).toDp() }
                val radius = if (page) 0.dp else if (detached) 28.dp else homeRadius * (1f - p) + 28.dp * p
                SideEffect { anchor.contact.vertical = kotlin.math.abs(targetHeight - startHeight) > kotlin.math.abs(targetWidth - startWidth) }
                val dark = darkOverride ?: (source?.dark == true)
                val material = rememberFrostedBackdrop(source?.fallback to (p == 1f), dark = dark)
                SideEffect { material.parent = source }
                // This recording is sampled only by a later nested modal. Its
                // material already has a shared raster; don't allocate and redraw
                // another full-panel texture throughout every opening/closing.
                val completePanel = rememberFrostedBackdrop(p == 1f, dark = dark, retainRaster = false)
                SideEffect { completePanel.parent = source }
                fun mix(a: Float, b: Float) = a + (b - a) * geometry
                Box(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.backdropPosition(view) }) {
                    Layout(modifier = Modifier.offset {
                        val rightAligned = start.center.x > availableWidth / 2f
                        val x = if (placement == GlassPlacement.Menu)
                            (if (anchor.contextual) start.center.x - targetWidth / 2f else if (rightAligned) start.right - targetWidth else start.left).coerceIn(margin, (availableWidth - targetWidth - margin).coerceAtLeast(margin))
                            else (availableWidth - targetWidth) / 2f
                        val y = when (placement) {
                            GlassPlacement.Search -> searchY.value
                            GlassPlacement.Center -> (availableHeight - targetHeight) / 2f
                            GlassPlacement.Sheet -> (availableHeight - targetHeight - margin).coerceAtLeast(margin)
                            GlassPlacement.Page -> 0f
                            GlassPlacement.Menu -> (if (anchor.contextual) minOf(start.top,
                                (anchor.bottomLimit.takeIf { it.isFinite() }?.minus(windowOrigin.y) ?: (availableHeight - anchor.bottomClearance)) - targetHeight)
                                else if (start.center.y > availableHeight / 2f) start.bottom - targetHeight else start.top)
                                .coerceIn(margin, (availableHeight - targetHeight).toFloat().coerceAtLeast(margin))
                        }
                        if (page) androidx.compose.ui.unit.IntOffset(with(density) { (28.dp.toPx() * (1f - p)).roundToInt() }, 0)
                        else androidx.compose.ui.unit.IntOffset(mix(if (detached) x else start.left, x).roundToInt(),
                            mix(if (detached) y + margin * 2 else start.top, y).roundToInt())
                    }.graphicsLayer {
                        alpha = if (detached) p else if (surfaceReady) 1f else 0f
                    }.drawWithContent {
                        // Match the trigger's draw-space deformation. A layout-layer
                        // scale changes global sampling coordinates, causing a small
                        // optical jump when the same body returns to its trigger.
                        val shape = if (reduced || page) GlassShape() else body.value
                        scale(shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical)) {
                            this@drawWithContent.drawContent()
                        }
                    }
                        .clearGlassShadow(LocalGlassAppearance.current.shadowStrength *
                            (if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else if (detached) p else 1f),
                            LocalGlassAppearance.current.shadowSpread, radius)
                        .clip(RoundedCornerShape(radius)).recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.graphicsLayer {
                            if (anchor.quiet) { val t = (p / .25f).coerceIn(0f, 1f); alpha = t * t * (3f - 2f * t) }
                        }.recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                LiquidGlassSurface(Modifier.fillMaxSize(), source = source,
                                    homeSource = anchor.source, homeWeight = { val home = (1f - p / .5f).coerceIn(0f, 1f); home * home * (3f - 2f * home) },
                                    cornerRadius = radius, frostProgress = p, panelProgress = p, panelDark = dark,
                                    inkOverride = anchor.ink.takeIf { it != Color.Unspecified }
                                        ?: if (dark) Color.White else Color(0xFF202124),
                                    pressFeedback = false, shadow = false,
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
                                LocalGlassOrigin provides null,
                                LocalGlassPanelReady provides (visible && p >= .9f)) {
                                GlassPanelTheme(dark, content)
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = (1f - p / .32f).coerceIn(0f, 1f) }
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
                        val width = (if (page) targetWidth else mix(if (detached) targetWidth * .96f else startWidth, targetWidth.toFloat()).roundToInt()).coerceIn(1, availableWidth)
                        val height = (if (page) foreground.height else if (!detached && geometry < 0f)
                            (startHeight * (1f - geometry * 2f)).roundToInt()
                        else mix(if (detached) foreground.height * .96f else startHeight,
                            expandedHeight.value.takeIf { it >= 0f } ?: foreground.height.toFloat()).roundToInt()).coerceIn(1, availableHeight)
                        val backing = children[0].measure(Constraints.fixed(width, height))
                        val face = children[2].measure(Constraints.fixed(startWidth.roundToInt().coerceAtLeast(1), startHeight.roundToInt().coerceAtLeast(1)))
                        layout(width, height) {
                            backing.place(0, 0)
                            foreground.placeWithLayer((width - foreground.width) / 2, (height - foreground.height) / 2) {
                                // Reveal the whole fixed-layout menu, not a moving crop of its
                                // middle rows. A uniform scale keeps glyph proportions intact.
                                val fit = if (page) 1f else minOf(width.toFloat() / foreground.width.coerceAtLeast(1),
                                    height.toFloat() / foreground.height.coerceAtLeast(1)).coerceAtMost(1f)
                                scaleX = fit; scaleY = fit
                            }
                            face.place((width - face.width) / 2, (height - face.height) / 2)
                        }
                    }
                }
            }
        }
    } }
}
