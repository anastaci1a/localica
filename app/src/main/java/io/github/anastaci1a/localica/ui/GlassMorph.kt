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
    var bounds by mutableStateOf(Rect.Zero)
    var hidden by mutableStateOf(false)
    var modalPresent by mutableStateOf(false)
    var source: FrostedBackdrop? = null
    var face: (@Composable () -> Unit)? = null
    var quiet = false
    var ink by mutableStateOf(Color.Unspecified)
}

@Composable
internal fun rememberGlassAnchor(): GlassAnchor {
    val ink = LocalContentColor.current
    return remember { GlassAnchor().also { it.ink = ink } }
}

@Composable
internal fun GlassAnchor.captureInk() {
    val color = LocalContentColor.current
    SideEffect { ink = color }
}

@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
        // Read visibility in drawing, not only in a child RenderNode's alpha. Recorded
        // backdrop parents must re-record the restored trigger even while stationary.
        .drawWithContent { if (!anchor.hidden) drawContent() }
        .then(if (anchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)
}

internal enum class GlassPlacement { Search, Menu, Center, Sheet, Page }
internal val LocalGlassPanelReady = compositionLocalOf { true }
// Only separate modal windows may read this scene. Inline lenses must keep using
// LocalFrostedBackdrop, whose recordings exclude their own foreground controls.
internal val LocalModalBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }

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
    val progress = remember { Animatable(0f) }
    val body = rememberGlassBody({ progress.value })
    var present by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    val ambientSource = LocalModalBackdrop.current
    LaunchedEffect(visible, reduced, speed) {
        if (!visible && !present) { anchor.hidden = false; return@LaunchedEffect }
        if (visible) {
            if (!present && anchor.bounds == Rect.Zero) withFrameNanos { }
            if (!present) { origin = anchor.bounds; source = ambientSource ?: anchor.source }
            source?.refresh()
            present = true; anchor.modalPresent = true
            // Keep the origin visible until the modal has a measured surface.
            withFrameNanos { }; withFrameNanos { }
            anchor.hidden = true; surfaceReady = true
        }
        if (reduced) progress.snapTo(if (visible) 1f else 0f)
        else progress.animateTo(if (visible) 1f else 0f,
            spring(dampingRatio = 1f, stiffness = 650f * speed * speed))
        if (!visible) {
            // The underlying window must draw its restored trigger before the modal
            // disappears. Keep the identical returned surface across that handoff.
            anchor.hidden = false; surfaceReady = false
            withFrameNanos { }; withFrameNanos { }
            present = false; surfaceReady = false; anchor.modalPresent = false
        }
    }
    DisposableEffect(anchor) { onDispose { anchor.hidden = false; anchor.modalPresent = false } }
    if (!present) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { TextEditingFocusHost {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        @Suppress("DEPRECATION")
        SideEffect {
            window?.let {
                it.setWindowAnimations(0); it.setDimAmount(0f)
                // A dismissed panel may finish drawing, but no longer owns input.
                // Otherwise its transparent full-screen window blocks the next action.
                val closingFlags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                if (visible) it.clearFlags(closingFlags) else it.addFlags(closingFlags)
                it.statusBarColor = android.graphics.Color.TRANSPARENT
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    it.isStatusBarContrastEnforced = false; it.isNavigationBarContrastEnforced = false
                }
                androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !(darkOverride ?: (source?.dark == true))
                    isAppearanceLightNavigationBars = !(darkOverride ?: (source?.dark == true))
                }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(visible) { if (!visible) keyboard?.hide() }
        val p = progress.value.coerceIn(0f, 1f)
        val geometry = if (anchor.quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f * p))
            .pointerInput(visible) { detectTapGestures { if (visible) onDismiss() } }) {
            BoxWithConstraints(Modifier.fillMaxSize().then(if (placement == GlassPlacement.Page) Modifier else Modifier.safeDrawingPadding().imePadding())) {
                val density = LocalDensity.current
                var windowOrigin by remember { mutableStateOf(Offset.Zero) }
                var targetHeight by remember { mutableIntStateOf(0) }
                val expandedHeight = remember { Animatable(-1f) }
                LaunchedEffect(targetHeight, reduced, speed) {
                    if (targetHeight > 0) {
                        if (expandedHeight.value < 0f || reduced) expandedHeight.snapTo(targetHeight.toFloat())
                        else expandedHeight.animateTo(targetHeight.toFloat(), spring(dampingRatio = .9f, stiffness = 450f * speed * speed))
                    }
                }
                val page = placement == GlassPlacement.Page
                val margin = with(density) { if (page) 0f else 12.dp.toPx() }
                val availableWidth = constraints.maxWidth
                val availableHeight = constraints.maxHeight
                val widthLimit = minOf(availableWidth - margin * 2, with(density) {
                    when (placement) { GlassPlacement.Menu -> if (fitContent) availableWidth.toFloat() else 400.dp.toPx(); GlassPlacement.Center, GlassPlacement.Sheet -> 400.dp.toPx()
                        GlassPlacement.Search, GlassPlacement.Page -> availableWidth.toFloat() }
                }).roundToInt().coerceAtLeast(1)
                var measuredWidth by remember { mutableIntStateOf(-1) }
                val expandedWidth = remember { Animatable(-1f) }
                LaunchedEffect(measuredWidth, reduced, speed) {
                    if (measuredWidth <= 0) return@LaunchedEffect
                    if (expandedWidth.value < 0f || reduced) expandedWidth.snapTo(measuredWidth.toFloat())
                    else expandedWidth.animateTo(measuredWidth.toFloat(), spring(1f, 650f * speed * speed))
                }
                val targetWidth = (expandedWidth.value.takeIf { it > 0f } ?: measuredWidth.takeIf { it > 0 }?.toFloat() ?: widthLimit.toFloat()).roundToInt()
                val maxHeight = (availableHeight - margin * 2).roundToInt().coerceAtLeast(1)
                // Closing follows the restored trigger. Opening keeps the pre-collapse location.
                val start = (if (visible) origin else anchor.bounds).translate(-windowOrigin)
                val detached = page || (origin == Rect.Zero && anchor.bounds == Rect.Zero)
                val startWidth = start.width.takeIf { it > 0f } ?: targetWidth.toFloat()
                val startHeight = start.height.takeIf { it > 0f } ?: with(density) { CompactControlSize.toPx() }
                val radius = if (page) 0.dp else if (detached) 28.dp else with(density) { (startHeight / 2 * (1f - p)).toDp() } + 28.dp * p
                val dark = darkOverride ?: (source?.dark == true)
                val material = rememberFrostedBackdrop(source?.fallback to (p == 1f), dark = dark)
                val completePanel = rememberFrostedBackdrop(p == 1f, dark = dark)
                fun mix(a: Float, b: Float) = a + (b - a) * geometry
                Box(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.backdropPosition(view) }) {
                    Layout(modifier = Modifier.offset {
                        val rightAligned = start.center.x > availableWidth / 2f
                        val x = if (placement == GlassPlacement.Menu)
                            (if (rightAligned) start.right - targetWidth else start.left).coerceIn(margin, (availableWidth - targetWidth - margin).coerceAtLeast(margin))
                            else (availableWidth - targetWidth) / 2f
                        val y = when (placement) {
                            GlassPlacement.Search -> margin
                            GlassPlacement.Center -> (availableHeight - targetHeight) / 2f
                            GlassPlacement.Sheet -> (availableHeight - targetHeight - margin).coerceAtLeast(margin)
                            GlassPlacement.Page -> 0f
                            GlassPlacement.Menu -> (if (start.center.y > availableHeight / 2f) start.bottom - targetHeight else start.top)
                                .coerceIn(margin, (availableHeight - targetHeight - margin).coerceAtLeast(margin))
                        }
                        if (page) androidx.compose.ui.unit.IntOffset(with(density) { (28.dp.toPx() * (1f - p)).roundToInt() }, 0)
                        else androidx.compose.ui.unit.IntOffset(mix(if (detached) x else start.left, x).roundToInt(),
                            mix(if (detached) y + margin * 2 else start.top, y).roundToInt())
                    }.graphicsLayer {
                        alpha = if (detached) p else if (surfaceReady) 1f else 0f
                        val direction = if (kotlin.math.abs(targetWidth - startWidth) >= kotlin.math.abs(targetHeight - startHeight)) 1f else -1f
                        // The returning trigger has its resting footprint. Fade deformation
                        // with travel near home rather than waiting for a low-damping tail.
                        val shape = if (reduced || page) GlassShape() else body.value.let {
                            GlassShape(it.strain * p, it.depth * p)
                        }
                        scaleX = shape.scaleX(direction < 0); scaleY = shape.scaleY(direction < 0)
                    }
                        .clearGlassShadow(LocalGlassAppearance.current.shadowStrength *
                            (if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else if (detached) p else 1f),
                            LocalGlassAppearance.current.shadowSpread, radius)
                        .clip(RoundedCornerShape(radius)).recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                LiquidGlassSurface(Modifier.fillMaxSize(), source = source,
                                    cornerRadius = radius, frostProgress = p, panelProgress = p, panelDark = dark,
                                    inkOverride = anchor.ink.takeIf { it != Color.Unspecified }
                                        ?: if (dark) Color.White else Color(0xFF202124),
                                    pressFeedback = false, shadow = false,
                                    body = { body.value.let { GlassShape(it.strain * p, it.depth * p) } },
                                    refraction = if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else 1f)
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = if (page) 1f else ((p - .18f) / .65f).coerceIn(0f, 1f) }
                            .then(if (!visible || p < .85f) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides if (dark) Color.White else Color(0xFF202124),
                                LocalModalBackdrop provides completePanel,
                                LocalGlassOrigin provides null,
                                LocalGlassPanelReady provides (visible && p >= .9f)) {
                                GlassPanelTheme(dark, content)
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = (1f - p / .32f).coerceIn(0f, 1f) }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
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
