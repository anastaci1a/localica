package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.previewContactLayer
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** Bounded pressure from travel beyond an edge: more pull always adds less compression. */
internal fun glassEdgePressure(excess: Float) = io.github.anastaci1a.localica.ui.engine.glassContactPressure(excess)

/** Continuous strong wells, shared by direct dragging and page-driven selectors. */
internal fun magneticChoicePosition(raw: Float): Float {
    val center = round(raw)
    val delta = raw - center
    return center + delta * (0.04f + 3.84f * delta * delta)
}

/** Deliberately slower than transport/page motion, even in Faster animations mode. */
internal fun glassMorphSpring(speed: Float) = io.github.anastaci1a.localica.ui.engine.glassMorphAnimationSpec(speed)

/** One interruptible shape clock; live tuning preserves its current displacement and velocity. */
@Stable internal class GlassBodyState {
    val output = mutableStateOf(GlassShape())
    val momentum = GlassMomentum()
    var impulseScale = 1f
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
                    body.advance(((current - state.previous) / dt / speed).coerceIn(-20f, 20f), dt * speed, force(), touch(), state.impulseScale)
                    state.previous = current
                    result.value = body.shape
                } while (!body.settled)
                // Epsilon convergence ends the clock, but must not leave a residual
                // shape across an integer-layout rounding boundary. Cancellation or
                // ownership handoff exits above without changing the shared body.
                body.finishSettlement()
                result.value = body.shape
            }
        }
    }
    return result
}

/** Contact/travel composes with the existing glass body; it never changes the frozen solver or optics. */
@Stable internal class GlassPreviewContact internal constructor(
    internal val contact: io.github.anastaci1a.localica.ui.engine.PreviewContact,
    internal val body: State<GlassShape>,
) {
    fun pressed(value: Boolean) = contact.contact(value)
    fun start() = contact.start()
    fun dragBy(delta: Float) = contact.dragBy(delta)
    fun release() = contact.release()
}

@Composable internal fun rememberGlassPreviewContact(limit: () -> Float, reduced: Boolean): GlassPreviewContact {
    val contact = io.github.anastaci1a.localica.ui.engine.rememberPreviewContactOwner(limit, reduced)
    val body = rememberGlassBody({ contact.position }, pressure = { contact.pressure }, contact = { if (contact.pressed) 1f else 0f })
    return remember(contact, body) { GlassPreviewContact(contact, body) }
}

internal fun androidx.compose.ui.Modifier.glassPreview(contact: GlassPreviewContact): androidx.compose.ui.Modifier =
    previewContactLayer(contact.contact, { contact.body.value.scaleX() }, { contact.body.value.scaleY() })
