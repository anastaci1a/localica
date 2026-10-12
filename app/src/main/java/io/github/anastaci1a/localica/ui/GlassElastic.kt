package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** Bounded pressure from travel beyond an edge: more pull always adds less compression. */
internal fun glassEdgePressure(excess: Float) = -.22f * (1f - exp(-abs(excess) * 2.4f))

/** One interruptible shape clock; live tuning preserves its current displacement and velocity. */
@Composable
internal fun rememberGlassBody(position: () -> Float, pressure: () -> Float = { 0f }, contact: () -> Float = { 0f }): State<GlassShape> {
    val result = remember { mutableStateOf(GlassShape()) }
    val reduced = LocalReducedMotion.current
    val speed by rememberUpdatedState(LocalMotionSpeed.current)
    val damping by rememberUpdatedState(LocalGlassAppearance.current.shapeDamping)
    val input by rememberUpdatedState(position)
    val force by rememberUpdatedState(pressure)
    val touch by rememberUpdatedState(contact)
    LaunchedEffect(reduced) {
        val body = GlassMomentum(damping)
        var previous = input()
        var runner: Job? = null
        snapshotFlow { listOf(input(), force(), touch(), damping) }.collect {
            if (reduced) { result.value = GlassShape(); previous = input(); return@collect }
            if (runner?.isActive != true) runner = launch {
                var last = 0L
                do {
                    val frame = withFrameNanos { it }
                    val dt = if (last == 0L) 1f / 60f else ((frame - last) / 1e9f).coerceIn(.001f, .1f)
                    last = frame
                    val current = input()
                    body.damping = damping
                    body.advance(((current - previous) / dt / speed).coerceIn(-20f, 20f), dt * speed, force(), touch())
                    previous = current
                    result.value = body.shape
                } while (!body.settled)
            }
        }
    }
    return result
}
