package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** A gesture owns its target. A removed target never leaves a cached rectangle for
 * the next scrub, and placement and pointer input read the same moving geometry. */
@Stable
internal class SeekCancelTargetState {
    var active by mutableStateOf(false)
        private set
    var generation by mutableIntStateOf(0)
        private set
    var host by mutableStateOf<LayoutCoordinates?>(null)
    var hostSize by mutableStateOf(IntSize.Zero)
    var hostOrigin by mutableStateOf(Offset.Zero)
    var motion by mutableStateOf<FloatingControlMotion?>(null)
    var size by mutableFloatStateOf(0f)
    var bottomInset by mutableFloatStateOf(0f)
    var rise by mutableFloatStateOf(0f)

    fun update(active: Boolean) {
        if (active != this.active) {
            this.active = active
            // Clear synchronously, before a fresh gesture can send its next event.
            host = null
            motion = null
            if (active) generation++
        }
    }
    val placement: IntOffset get() {
        if (host?.isAttached != true) return IntOffset.Zero
        return IntOffset(((hostSize.width - size) / 2f).roundToInt(),
            (hostSize.height - size - bottomInset + (motion?.offsetY(rise) ?: 0f)).roundToInt())
    }
    val bounds: Rect? get() {
        if (host?.isAttached != true) return null
        if (!active || motion?.interactive != true || size <= 0f) return null
        val at = placement
        return Rect(hostOrigin + Offset(at.x.toFloat(), at.y.toFloat()), Size(size, size))
    }
}

@Composable
internal fun SeekCancelTarget(state: SeekCancelTargetState, hovered: Boolean, bottomInset: Dp,
    reducedMotion: Boolean) {
    key(state.generation) {
        val density = LocalDensity.current
        val motion = rememberFloatingControlMotion(true, reducedMotion)
        DisposableEffect(state, motion) {
            state.motion = motion
            onDispose { if (state.motion === motion) { state.motion = null; state.host = null } }
        }
        SideEffect {
            state.size = with(density) { CompactControlSize.roundToPx().toFloat() }
            state.bottomInset = with(density) { bottomInset.toPx() }
            state.rise = with(density) { FloatingControlRise.toPx() }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned {
            state.host = it
            state.hostSize = it.size
            state.hostOrigin = it.positionInRoot()
        }) {
            if (motion.alpha > 0f) DragCancelTarget(hovered, "seek",
                Modifier.offset { state.placement }.graphicsLayer { alpha = motion.alpha }.testTag("cancel-seek"))
        }
    }
}
