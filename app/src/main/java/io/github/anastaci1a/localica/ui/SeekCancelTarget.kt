package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
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
    var motion by mutableStateOf<SeekCancelTargetMotion?>(null)
    var size by mutableFloatStateOf(0f)
    var bottomInset by mutableFloatStateOf(0f)
    var rise by mutableFloatStateOf(0f)

    private var placedGeneration by mutableIntStateOf(-1)
    internal var cancelled by mutableStateOf(false)

    fun placed(generation: Int) { placedGeneration = generation }

    fun update(active: Boolean, cancelled: Boolean = false) {
        if (active != this.active) {
            motion?.hold()
            this.active = active
            // Revoke hit geometry synchronously, but retain the exact drawn placement.
            // A new gesture must be presented before it can use this target.
            if (active) generation++
            this.cancelled = !active && cancelled
        }
    }
    val placement: IntOffset get() {
        if (host?.isAttached != true) return IntOffset.Zero
        return IntOffset(((hostSize.width - size) / 2f).roundToInt(),
            (hostSize.height - size - bottomInset + (motion?.offsetY(rise) ?: rise)).roundToInt())
    }
    val bounds: Rect? get() {
        if (host?.isAttached != true) return null
        if (!active || placedGeneration != generation || motion?.interactive != true || size <= 0f) return null
        val at = placement
        return Rect(hostOrigin + Offset(at.x.toFloat(), at.y.toFloat()), Size(size, size))
    }
}

/** One vector keeps opacity and rise on the same frame. Event-time holds bridge the
 * interval before LaunchedEffect cancels the old animation and starts its successor. */
@Stable
internal class SeekCancelTargetMotion(private val visible: State<Boolean>, private val reduced: State<Boolean>) {
    private val animation = Animatable(Offset(0f, 1f), Offset.VectorConverter)
    private var held by mutableStateOf<Offset?>(null)
    private val shown: Offset get() = if (reduced.value) {
        if (visible.value) Offset(1f, 0f) else Offset(0f, 1f)
    } else held ?: animation.value
    val progress: Float get() = shown.x
    val alpha: Float get() = progress
    val interactive: Boolean get() = visible.value && alpha > 0f
    fun offsetY(distance: Float): Float = shown.y * distance
    fun hold() { held = shown }
    suspend fun animate(active: Boolean, cancelled: Boolean, reducedMotion: Boolean, speed: Float) {
        val start = held ?: animation.value
        animation.snapTo(start)
        held = null
        val end = if (active) Offset(1f, 0f) else Offset(0f, if (cancelled) start.y else 1f)
        if (reducedMotion) animation.snapTo(end)
        else animation.animateTo(end, tween(floatingControlDuration(false, speed), easing = LinearOutSlowInEasing))
    }
}

@Composable
internal fun SeekCancelTarget(state: SeekCancelTargetState, hovered: Boolean, bottomInset: Dp,
    reducedMotion: Boolean, action: String = "seek", tag: String = "cancel-seek") {
    val playerTheme = LocalPlayerDrawTheme.current
    val density = LocalDensity.current
    val reduced = rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current)
    val speed = LocalMotionSpeed.current
    val visible = rememberUpdatedState(state.active)
    val policy = rememberUpdatedState(reduced)
    val motion = remember { SeekCancelTargetMotion(visible, policy) }
    val generation = state.generation
    LaunchedEffect(state.active, state.cancelled, generation, reduced, speed) {
        motion.animate(state.active, state.cancelled, reduced, speed)
    }
    LaunchedEffect(generation) { withFrameNanos { state.placed(generation) } }
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
        if (motion.alpha > 0f) {
            @Composable fun Target() { DragCancelTarget(hovered || state.cancelled, action,
                Modifier.glassShadowOffset { state.placement }.glassShadowOpacity { motion.alpha }.testTag(tag)) }
            if (playerTheme == null) Target() else PlayerMaterialTheme(playerTheme) { Target() }
        }
    }
}
