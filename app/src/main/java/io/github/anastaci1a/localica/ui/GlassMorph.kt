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
    var source: FrostedBackdrop? = null
    var face: (@Composable () -> Unit)? = null
    var quiet = false
}

@Composable
internal fun rememberGlassAnchor() = remember { GlassAnchor() }

@Composable
internal fun Modifier.glassAnchor(anchor: GlassAnchor): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { anchor.bounds = Rect(it.backdropPosition(view), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
        .graphicsLayer { alpha = if (anchor.hidden) 0f else 1f }
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
    placement: GlassPlacement = GlassPlacement.Menu, darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val progress = remember { Animatable(0f) }
    var deformation by remember { mutableFloatStateOf(0f) }
    val momentum = remember { GlassMomentum() }
    var present by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    val ambientSource = LocalModalBackdrop.current
    LaunchedEffect(visible, reduced, speed) {
        if (!visible && !present) { anchor.hidden = false; return@LaunchedEffect }
        if (visible) {
            if (!present && anchor.bounds == Rect.Zero) withFrameNanos { }
            if (!present) { origin = anchor.bounds; source = ambientSource ?: anchor.source }
            source?.refresh()
            present = true
            // Keep the origin visible until the modal has a measured surface.
            withFrameNanos { }; withFrameNanos { }
            anchor.hidden = true
        }
        if (reduced) { progress.snapTo(if (visible) 1f else 0f); deformation = 0f; momentum.reset() }
        else {
            val initial = progress.value
            val target = if (visible) 1f else 0f
            val duration = 0.46f / speed
            val begin = withFrameNanos { it }
            var lastFrame = begin
            var previous = initial
            do {
                val now = withFrameNanos { it }
                val dt = (now - lastFrame) / 1_000_000_000f
                val fraction = ((now - begin) / 1_000_000_000f / duration).coerceIn(0f, 1f)
                val next = initial + (target - initial) * androidx.compose.animation.core.FastOutSlowInEasing.transform(fraction)
                momentum.advance((next - previous) / dt.coerceAtLeast(.001f) / speed, dt * speed)
                deformation = momentum.strain
                progress.snapTo(next)
                previous = next
                lastFrame = now
            } while (fraction < 1f)
            // Dissipate the actual remaining energy; reversals retain it naturally.
            while (!momentum.settled) {
                val now = withFrameNanos { it }
                momentum.advance(0f, (now - lastFrame) / 1_000_000_000f * speed)
                lastFrame = now
                deformation = momentum.strain
            }
            deformation = 0f
        }
        if (!visible) { present = false; anchor.hidden = false }
    }
    DisposableEffect(anchor) { onDispose { anchor.hidden = false } }
    if (!present) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        @Suppress("DEPRECATION")
        SideEffect {
            window?.let {
                it.setWindowAnimations(0); it.setDimAmount(0f)
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
                val targetWidth = minOf(availableWidth - margin * 2, with(density) {
                    when (placement) { GlassPlacement.Menu -> 300.dp.toPx(); GlassPlacement.Center, GlassPlacement.Sheet -> 400.dp.toPx()
                        GlassPlacement.Search, GlassPlacement.Page -> availableWidth.toFloat() }
                }).roundToInt().coerceAtLeast(1)
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
                        alpha = if (detached) p else if (anchor.hidden) 1f else 0f
                        val direction = if (kotlin.math.abs(targetWidth - startWidth) >= kotlin.math.abs(targetHeight - startHeight)) 1f else -1f
                        val strain = if (reduced || page) 0f else deformation * direction
                        scaleX = kotlin.math.exp(strain); scaleY = kotlin.math.exp(-strain)
                    }
                        .clip(RoundedCornerShape(radius)).recordFrostedBackdrop(completePanel).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.recordFrostedBackdrop(material)) {
                            if (page) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                            else {
                                if (p < 1f) LiquidGlassSurface(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - p) * (if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else 1f) },
                                    source = source, cornerRadius = radius, frostProgress = p, pressFeedback = false,
                                    refraction = if (anchor.quiet) (p / .14f).coerceIn(0f, 1f) else 1f)
                                FrostedSurface(Modifier.fillMaxSize().graphicsLayer { alpha = p },
                                    shape = RoundedCornerShape(radius), source = source) {}
                            }
                        }
                        Box(Modifier.graphicsLayer { alpha = if (page) 1f else ((p - .18f) / .65f).coerceIn(0f, 1f) }
                            .then(if (!visible || p < .85f) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides if (dark) Color.White else Color(0xFF202124),
                                LocalModalBackdrop provides completePanel,
                                LocalGlassOrigin provides null,
                                LocalGlassPanelReady provides (visible && p >= .9f)) { content() }
                        }
                        Box(Modifier.graphicsLayer { alpha = (1f - p / .32f).coerceIn(0f, 1f) }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else Color(0xFF202124)) { anchor.face?.invoke() }
                        }
                    }) { children, _ ->
                        val foreground = children[1].measure(Constraints(minWidth = targetWidth, maxWidth = targetWidth,
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
    }
}
