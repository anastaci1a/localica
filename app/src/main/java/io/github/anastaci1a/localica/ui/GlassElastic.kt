package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** Bounded pressure from travel beyond an edge: more pull always adds less compression. */
internal fun glassEdgePressure(excess: Float) = -.22f * (1f - exp(-abs(excess) * 2.4f))

/** Shared input-driven elastic response. The clock sleeps completely when settled. */
@Composable
internal fun rememberGlassElastic(position: () -> Float, pressure: () -> Float = { 0f }): State<Float> {
    val result = remember { mutableFloatStateOf(0f) }
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val input by rememberUpdatedState(position)
    val force by rememberUpdatedState(pressure)
    LaunchedEffect(reduced, speed) {
        val body = GlassMomentum()
        var previous = input()
        var runner: Job? = null
        snapshotFlow { input() to force() }.collect {
            if (reduced) { result.floatValue = 0f; previous = input(); return@collect }
            if (runner?.isActive != true) runner = launch {
                var last = withFrameNanos { it }
                do {
                    val frame = withFrameNanos { it }
                    val dt = ((frame - last) / 1e9f).coerceIn(.001f, .05f)
                    last = frame
                    val current = input()
                    body.advance(((current - previous) / dt / speed).coerceIn(-20f, 20f), dt * speed, force())
                    previous = current
                    result.floatValue = body.strain
                } while (!body.settled)
                result.floatValue = force()
            }
        }
    }
    return result
}
