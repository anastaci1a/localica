package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
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

internal enum class GlassPlacement { Search, Menu, Center }
internal val LocalGlassPanelReady = compositionLocalOf { true }
// Only separate modal windows may read this scene. Inline lenses must keep using
// LocalFrostedBackdrop, whose recordings exclude their own foreground controls.
internal val LocalModalBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }

/** Keep this call composed when closed so the same surface can return to its trigger. */
@Composable
internal fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit,
    placement: GlassPlacement = GlassPlacement.Menu, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var source by remember { mutableStateOf<FrostedBackdrop?>(null) }
    val ambientSource = LocalModalBackdrop.current
    LaunchedEffect(visible, reduced) {
        if (visible) {
            if (!present && anchor.bounds == Rect.Zero) withFrameNanos { }
            if (!present) { origin = anchor.bounds; source = ambientSource ?: anchor.source }
            present = true; anchor.hidden = true
        }
        if (reduced) progress.snapTo(if (visible) 1f else 0f)
        else progress.animateTo(if (visible) 1f else 0f, spring(dampingRatio = .86f, stiffness = 420f))
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
                    isAppearanceLightStatusBars = source?.dark != true
                    isAppearanceLightNavigationBars = source?.dark != true
                }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(visible) { if (!visible) keyboard?.hide() }
        val p = progress.value.coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f * p))
            .pointerInput(visible) { detectTapGestures { if (visible) onDismiss() } }) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val density = LocalDensity.current
                var windowOrigin by remember { mutableStateOf(Offset.Zero) }
                var targetHeight by remember { mutableIntStateOf(0) }
                val expandedHeight = remember { Animatable(-1f) }
                LaunchedEffect(targetHeight, reduced) {
                    if (targetHeight > 0) {
                        if (expandedHeight.value < 0f || reduced) expandedHeight.snapTo(targetHeight.toFloat())
                        else expandedHeight.animateTo(targetHeight.toFloat(), spring(dampingRatio = .9f, stiffness = 450f))
                    }
                }
                val margin = with(density) { 12.dp.toPx() }
                val availableWidth = constraints.maxWidth
                val availableHeight = constraints.maxHeight
                val targetWidth = (if (placement == GlassPlacement.Menu) minOf(with(density) { 300.dp.toPx() }, availableWidth - margin * 2)
                    else availableWidth - margin * 2).roundToInt().coerceAtLeast(1)
                val maxHeight = (availableHeight - margin * 2).roundToInt().coerceAtLeast(1)
                // Closing follows the restored trigger. Opening keeps the pre-collapse location.
                val start = (if (visible) origin else anchor.bounds).translate(-windowOrigin)
                val startWidth = start.width.takeIf { it > 0f } ?: targetWidth.toFloat()
                val startHeight = start.height.takeIf { it > 0f } ?: with(density) { CompactControlSize.toPx() }
                val radius = with(density) { (startHeight / 2 * (1f - p)).toDp() } + 28.dp * p
                val dark = source?.dark == true
                val material = rememberFrostedBackdrop(source?.fallback to (p == 1f), dark = dark)
                fun mix(a: Float, b: Float) = a + (b - a) * p
                Box(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.backdropPosition(view) }) {
                    Layout(modifier = Modifier.offset {
                        val rightAligned = start.center.x > availableWidth / 2f
                        val x = if (placement == GlassPlacement.Menu)
                            (if (rightAligned) start.right - targetWidth else start.left).coerceIn(margin, (availableWidth - targetWidth - margin).coerceAtLeast(margin))
                            else (availableWidth - targetWidth) / 2f
                        val y = when (placement) {
                            GlassPlacement.Search -> margin
                            GlassPlacement.Center -> (availableHeight - targetHeight) / 2f
                            GlassPlacement.Menu -> (if (start.center.y > availableHeight / 2f) start.bottom - targetHeight else start.top)
                                .coerceIn(margin, (availableHeight - targetHeight - margin).coerceAtLeast(margin))
                        }
                        androidx.compose.ui.unit.IntOffset(mix(start.left, x).roundToInt(), mix(start.top, y).roundToInt())
                    }.clip(RoundedCornerShape(radius)).testTag("glass-morph-surface")
                        .pointerInput(Unit) { detectTapGestures { } }, content = {
                        Box(Modifier.recordFrostedBackdrop(material)) {
                            LiquidGlassSurface(Modifier.fillMaxSize(), source = source, cornerRadius = radius, frostProgress = p,
                                tint = (if (dark) Color.Black else Color.White).copy(alpha = p * if (dark) .5f else .86f), pressFeedback = false)
                        }
                        Box(Modifier.graphicsLayer { alpha = ((p - .18f) / .65f).coerceIn(0f, 1f) }
                            .then(if (!visible || p < .85f) Modifier.clearAndSetSemantics { } else Modifier)) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides material, LocalContentColor provides if (dark) Color.White else Color(0xFF202124),
                                LocalGlassPanelReady provides (visible && p >= .9f)) { content() }
                        }
                        Box(Modifier.graphicsLayer { alpha = (1f - p / .32f).coerceIn(0f, 1f) }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else Color(0xFF202124)) { anchor.face?.invoke() }
                        }
                    }) { children, _ ->
                        val foreground = children[1].measure(Constraints(minWidth = targetWidth, maxWidth = targetWidth, maxHeight = maxHeight))
                        targetHeight = foreground.height
                        val width = mix(startWidth, targetWidth.toFloat()).roundToInt().coerceAtLeast(1)
                        val height = mix(startHeight, expandedHeight.value.takeIf { it >= 0f } ?: foreground.height.toFloat()).roundToInt().coerceAtLeast(1)
                        val backing = children[0].measure(Constraints.fixed(width, height))
                        val face = children[2].measure(Constraints.fixed(startWidth.roundToInt().coerceAtLeast(1), startHeight.roundToInt().coerceAtLeast(1)))
                        layout(width, height) {
                            backing.place(0, 0)
                            foreground.place((width - foreground.width) / 2, (height - foreground.height) / 2)
                            face.place((width - face.width) / 2, (height - face.height) / 2)
                        }
                    }
                }
            }
        }
    }
}
