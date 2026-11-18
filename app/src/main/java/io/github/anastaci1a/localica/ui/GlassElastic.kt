package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** Bounded pressure from travel beyond an edge: more pull always adds less compression. */
internal fun glassEdgePressure(excess: Float) = -.22f * (1f - exp(-abs(excess) * 2.4f))

/** Continuous strong wells, shared by direct dragging and page-driven selectors. */
internal fun magneticChoicePosition(raw: Float): Float {
    val center = round(raw)
    val delta = raw - center
    return center + delta * (0.04f + 3.84f * delta * delta)
}

/** Deliberately slower than transport/page motion, even at a faster animation speed. */
internal fun glassMorphSpring(speed: Float) = androidx.compose.animation.core.spring<Float>(1f, 90f * speed * speed, visibilityThreshold = .0001f)

/** One interruptible shape clock; live tuning preserves its current displacement and velocity. */
@Stable internal class GlassBodyState(bodyOverride: FlexSpec? = null) {
    val output = mutableStateOf(GlassShape())
    val glow = mutableStateOf(GlassGlow())
    val momentum = GlassMomentum(bodyOverride)
    var impulseScale = 1f
    var previous = 0f
}

@Composable
internal fun rememberGlassBody(position: () -> Float, pressure: () -> Float = { 0f }, contact: () -> Float = { 0f },
    state: GlassBodyState = remember { GlassBodyState() }, active: () -> Boolean = { true }, point: (() -> Offset)? = null,
    vertical: () -> Boolean = { false }, lightTravel: () -> Float = { 0f }, generation: () -> Int = { 0 }): State<GlassShape> {
    val result = state.output
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val damping by rememberUpdatedState(LocalGlassAppearance.current.shapeDamping)
    val input by rememberUpdatedState(position)
    val force by rememberUpdatedState(pressure)
    val touch by rememberUpdatedState(contact)
    val glowPoint by rememberUpdatedState(point)
    val verticalAxis by rememberUpdatedState(vertical)
    val distance by rememberUpdatedState(lightTravel)
    val begin by rememberUpdatedState(generation)
    val strength by rememberUpdatedState(LocalGlassAppearance.current.touchLight)
    val ownsClock by rememberUpdatedState(active)
    LaunchedEffect(state, reduced, speed) {
        val body = state.momentum
        var runner: Job? = null
        snapshotFlow { listOf(input(), force(), touch(), damping, ownsClock(), if (touch() > 0f || strength > 0f) glowPoint?.invoke() else null, verticalAxis(), strength, if (strength > 0f) distance() else 0f, if (strength > 0f) begin() else 0) }.collect {
            if (!ownsClock()) { runner?.cancel(); runner = null; return@collect }
            if (reduced) { body.reset(); body.targetGlow(glowPoint?.invoke(), touch(), strength, snap = true, travel = distance(), generation = begin()); state.glow.value = body.glow; result.value = GlassShape(); state.previous = input(); return@collect }
            if (runner?.isActive != true) runner = launch {
                var last: Long? = null
                while (true) {
                    val frame = withFrameNanos { it }
                    // Ownership can change before snapshotFlow cancels this runner.
                    // Never integrate the same shared body twice at a window handoff.
                    if (!ownsClock()) return@launch
                    if (last != null && frame <= last) continue
                    val dt = last?.let { (frame - it).toDouble() * speed / 1e9 } ?: 0.0
                    val current = input()
                    if (last == null) state.previous = current
                    val displacement = current - state.previous
                    val touched = touch()
                    body.advance(if (dt > 0.0) (displacement / dt).toFloat().coerceIn(-20f, 20f) else 0f,
                        dt, force(), touched, state.impulseScale, damping,
                        glowPoint?.invoke()?.let { if (verticalAxis()) Offset(it.y, it.x) else it })
                    body.targetGlow(glowPoint?.invoke(), touched, strength, travel = distance(), generation = begin())
                    state.previous = current
                    last = frame
                    val finished = dt > 0.0 && displacement == 0f && body.finishSettlement()
                    result.value = body.shape
                    state.glow.value = body.glow
                    if (finished) break
                }
            }
        }
    }
    return result
}
