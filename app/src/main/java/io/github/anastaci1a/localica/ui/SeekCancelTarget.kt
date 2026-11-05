package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    internal var position: Animatable<Float, AnimationVector1D>? = null
    private var placedGeneration by mutableIntStateOf(-1)
    internal var exitPosition by mutableStateOf<Float?>(null)

    fun placed(generation: Int) { placedGeneration = generation }

    fun update(active: Boolean, cancelled: Boolean = false) {
        if (active != this.active) {
            this.active = active
            // Revoke hit geometry synchronously, but retain the exact drawn placement.
            // A new gesture must be presented before it can use this target.
            if (active) generation++
            exitPosition = if (!active && cancelled) position?.value else null
        }
    }
    val placement: IntOffset get() {
        if (host?.isAttached != true) return IntOffset.Zero
        return IntOffset(((hostSize.width - size) / 2f).roundToInt(),
            (hostSize.height - size - bottomInset + ((position?.value ?: 1f) * rise)).roundToInt())
    }
    val bounds: Rect? get() {
        if (host?.isAttached != true) return null
        if (!active || placedGeneration != generation || motion?.interactive != true || size <= 0f) return null
        val at = placement
        return Rect(hostOrigin + Offset(at.x.toFloat(), at.y.toFloat()), Size(size, size))
    }
}

@Composable
internal fun SeekCancelTarget(state: SeekCancelTargetState, hovered: Boolean, bottomInset: Dp,
    reducedMotion: Boolean, action: String = "seek", tag: String = "cancel-seek") {
    val density = LocalDensity.current
    val reduced = rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current)
    val speed = LocalMotionSpeed.current
    val motion = rememberFloatingControlMotion(state.active, reduced)
    val position = remember { Animatable(1f) }
    val destination = if (state.active) 0f else state.exitPosition ?: 1f
    LaunchedEffect(state.active, destination, reduced, speed) {
        if (reduced) position.snapTo(destination)
        else position.animateTo(destination, tween(floatingControlDuration(false, speed), easing = LinearOutSlowInEasing))
    }
    DisposableEffect(state, motion) {
        state.motion = motion
        state.position = position
        onDispose { if (state.motion === motion) { state.motion = null; state.position = null; state.host = null } }
    }
    val generation = state.generation
    SideEffect {
        state.placed(generation)
        state.size = with(density) { CompactControlSize.roundToPx().toFloat() }
        state.bottomInset = with(density) { bottomInset.toPx() }
        state.rise = with(density) { FloatingControlRise.toPx() }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        state.host = it
        state.hostSize = it.size
        state.hostOrigin = it.positionInRoot()
    }) {
        if (motion.alpha > 0f) DragCancelTarget(hovered || (!state.active && state.exitPosition != null), action,
            Modifier.glassShadowOffset { state.placement }.glassShadowOpacity { motion.alpha }.testTag(tag))
    }
}
