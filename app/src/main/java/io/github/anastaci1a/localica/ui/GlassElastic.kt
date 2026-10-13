package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
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

/** Deliberately slower than transport/page motion, even in Faster animations mode. */
internal fun glassMorphSpring(speed: Float) = androidx.compose.animation.core.spring<Float>(1f, 90f * speed * speed)

/** One interruptible shape clock; live tuning preserves its current displacement and velocity. */
@Stable internal class GlassBodyState {
    val output = mutableStateOf(GlassShape())
    val momentum = GlassMomentum()
    var previous = 0f
    var initialized = false
}

@Composable
internal fun rememberGlassBody(position: () -> Float, pressure: () -> Float = { 0f }, contact: () -> Float = { 0f },
    state: GlassBodyState = remember { GlassBodyState() }, active: () -> Boolean = { true }): State<GlassShape> {
    val result = state.output
    val reduced = LocalReducedMotion.current
    val speed by rememberUpdatedState(LocalMotionSpeed.current)
    val damping by rememberUpdatedState(LocalGlassAppearance.current.shapeDamping)
    val input by rememberUpdatedState(position)
    val force by rememberUpdatedState(pressure)
    val touch by rememberUpdatedState(contact)
    val ownsClock by rememberUpdatedState(active)
    LaunchedEffect(state, reduced) {
        val body = state.momentum
        var runner: Job? = null
        snapshotFlow { listOf(input(), force(), touch(), damping, if (ownsClock()) 1f else 0f) }.collect {
            if (!ownsClock()) { runner?.cancel(); runner = null; return@collect }
            if (!state.initialized) { state.previous = input(); state.initialized = true }
            if (reduced) { body.reset(); result.value = GlassShape(); state.previous = input(); return@collect }
            if (runner?.isActive != true) runner = launch {
                var last = 0L
                do {
                    val frame = withFrameNanos { it }
                    // Ownership can change before snapshotFlow cancels this runner.
                    // Never integrate the same shared body twice at a window handoff.
                    if (!ownsClock()) return@launch
                    val dt = if (last == 0L) 1f / 60f else ((frame - last) / 1e9f).coerceIn(.001f, .1f)
                    last = frame
                    val current = input()
                    body.damping = damping
                    body.advance(((current - state.previous) / dt / speed).coerceIn(-20f, 20f), dt * speed, force(), touch())
                    state.previous = current
                    result.value = body.shape
                } while (!body.settled)
            }
        }
    }
    return result
}
