package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import io.github.anastaci1a.localica.ui.components.FaceExpansion
import io.github.anastaci1a.localica.ui.components.rememberFaceExpansion
import io.github.anastaci1a.localica.ui.engine.CompositorFace
import io.github.anastaci1a.localica.ui.engine.Curve
import io.github.anastaci1a.localica.ui.engine.RetainedFaceHost
import io.github.anastaci1a.localica.ui.engine.retainedFaceProgress
import androidx.compose.ui.geometry.Rect
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
    height: MutableState<Dp>, internal val face: FaceExpansion, visible: State<Boolean>,
) {
    internal var height by height
    internal val progress get() = retainedFaceProgress(face.view)
    internal val visible by visible
    val clearance: Dp get() = SelectionPanelGeometry(height.value, progress, FloatingSurfaceGap.value).clearance.dp
}

@Composable
internal fun rememberFloatingSelectionPanel(visible: Boolean, reducedMotion: Boolean): FloatingSelectionPanelState {
    val height = remember { mutableStateOf(0.dp) }
    val visibility = rememberUpdatedState(visible)
    // Access policy comes from the root's validated Engine record, as for all faces.
    val face = rememberFaceExpansion(visible)
    return remember(face) { FloatingSelectionPanelState(height, face, visibility) }
}

@Composable
internal fun FloatingSelectionPanel(state: FloatingSelectionPanelState, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val density = LocalDensity.current
    RetainedFaceHost(state.face.view, state.visible, modifier, Modifier.layout { measurable, constraints ->
        val gap = FloatingSurfaceGap.roundToPx()
        val child = measurable.measure(constraints.copy(minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - gap).coerceAtLeast(0) else constraints.maxHeight))
        state.height = with(density) { child.height.toDp() }
        state.face.measured(Rect(0f, child.height.toFloat(), child.width.toFloat(), child.height.toFloat()),
            Rect(0f, 0f, child.width.toFloat(), child.height.toFloat()))
        val geometry = SelectionPanelGeometry(child.height.toFloat(), state.progress, FloatingSurfaceGap.toPx())
        // A bottom-anchored reveal occupies only its visible pixels, including the top gap.
        // Inline hosts shrink continuously too; they never lose a full panel at disposal.
        layout(child.width, constraints.constrainHeight(geometry.clearance.roundToInt())) {
            child.placeRelative(0, (geometry.clearance - geometry.occupiedHeight).roundToInt())
        }
    }) {
        CompositionLocalProvider(LocalSurfaceShadows provides null, LocalPageShadows provides null) {
            CompositorFace(state.visible, state.face.view, { state.face.bounds }, Curve.linear(0f, 1f),
                inset = glassShadowInset(), retainSize = true, content = content)
        }
    }
}
