package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** The same visible occupancy positions the panel and every control above bottom chrome. */
internal data class SelectionPanelGeometry(val height: Float, val progress: Float, val gap: Float) {
    val occupiedHeight: Float get() = height * progress.coerceIn(0f, 1f)
    val translation: Float get() = height - occupiedHeight
    val clearance: Float get() = occupiedHeight + gap * progress.coerceIn(0f, 1f)
}

internal val FloatingSurfaceGap = 8.dp

@Stable
internal class FloatingSelectionPanelState(
    height: MutableState<Dp>, progress: State<Float>, visible: State<Boolean>,
) {
    internal var height by height
    internal val progress by progress
    internal val visible by visible
    val clearance: Dp get() = SelectionPanelGeometry(height.value, progress, FloatingSurfaceGap.value).clearance.dp
}

@Composable
internal fun rememberFloatingSelectionPanel(visible: Boolean, reducedMotion: Boolean): FloatingSelectionPanelState {
    val height = remember { mutableStateOf(0.dp) }
    val visibility = rememberUpdatedState(visible)
    // Start only after measurement, so the first reveal cannot lift controls a frame late.
    val progress = animateFloatAsState(if (visible && height.value > 0.dp) 1f else 0f,
        selectionMotionSpec(reducedMotion, 220), label = "Selection panel occupancy")
    return remember { FloatingSelectionPanelState(height, progress, visibility) }
}

@Composable
internal fun FloatingSelectionPanel(state: FloatingSelectionPanelState, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (state.visible || state.progress > 0f) Box(modifier
        // Clip only during reveal; settled floating materials retain their external shadows.
        .then(if (state.progress < 1f) Modifier.clipToBounds() else Modifier)
        .layout { measurable, constraints ->
        val gap = FloatingSurfaceGap.roundToPx()
        val child = measurable.measure(constraints.copy(minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - gap).coerceAtLeast(0) else constraints.maxHeight))
        state.height = with(density) { child.height.toDp() }
        val geometry = SelectionPanelGeometry(child.height.toFloat(), state.progress, FloatingSurfaceGap.toPx())
        // A bottom-anchored reveal occupies only its visible pixels, including the top gap.
        // Inline hosts shrink continuously too; they never lose a full panel at disposal.
        layout(child.width, constraints.constrainHeight(geometry.clearance.roundToInt())) {
            child.placeRelative(0, (geometry.clearance - geometry.occupiedHeight).roundToInt())
        }
    }.graphicsLayer { alpha = state.progress }) { content() }
}
