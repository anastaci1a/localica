package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*

private class AppliedSelectorFrame(var frame: SelectorReturnFrame, var rest: SelectorReturnFrame,
    var held: Boolean, var wall: Float)
private data class SelectorReturnPhase(val release: SelectorCrossReturn, val regrab: Boolean)

/** Full displayed geometry is captured in SideEffect before cleared pressure can replace it. */
@Composable
internal fun rememberSelectorCrossFrame(raw: SelectorReturnFrame, rest: SelectorReturnFrame,
    held: Boolean, bodySettled: Boolean, limits: SelectorReturnLimits,
    enabled: Boolean = true): SelectorReturnFrame {
    val applied = remember { AppliedSelectorFrame(raw, rest, held, limits.wall) }
    var phase by remember { mutableStateOf<SelectorReturnPhase?>(null) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var releasedFrame by remember { mutableStateOf(raw) }
    val speed by rememberUpdatedState(LocalMotionSpeed.current)
    val damping by rememberUpdatedState(LocalGlassAppearance.current.shapeDamping)
    val settled by rememberUpdatedState(bodySettled)
    val reduced = LocalReducedMotion.current || !enabled
    val resized = applied.rest != rest
    val changedContact = applied.held != held
    val reversed = held && applied.wall != limits.wall
    val interrupted = phase != null
    val captured = applied.frame
    val output = when {
        reduced || resized -> raw
        (changedContact || reversed) && (!held || interrupted || captured != raw) -> captured
        phase?.regrab == true -> phase!!.release.start.toward(raw, 1f - selectorCriticalResidual(elapsed))
        phase != null -> releasedFrame
        else -> raw
    }
    SideEffect { applied.frame = output; applied.rest = rest; applied.held = held; applied.wall = limits.wall }
    LaunchedEffect(held, rest, reduced, limits.wall) {
        if (reduced || resized || (!changedContact && !reversed)) { phase = null; return@LaunchedEffect }
        if (held && !interrupted && captured == raw) { phase = null; return@LaunchedEffect }
        val release = SelectorCrossReturn(captured, if (held) raw else rest, limits)
        elapsed = 0f
        releasedFrame = captured
        phase = SelectorReturnPhase(release, regrab = held)
        var previousTime = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            val dt = ((now - previousTime) / 1e9f).coerceIn(.001f, .1f) * speed
            previousTime = now
            if (!held) releasedFrame = release.advance(dt, damping)
            elapsed += dt
        } while (if (held) selectorCriticalResidual(elapsed) >= .0003f else !release.settled || !settled)
        // Wait for the old optical body to settle before handing geometry back to it.
        // Its independently ringing strain/depth never leak into the displayed footprint.
        phase = null
    }
    return output
}
