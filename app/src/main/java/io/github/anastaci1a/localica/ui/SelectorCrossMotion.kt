package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*

private class AppliedSelectorCross(var frame: SelectorCrossFrame, var rest: SelectorCrossFrame, var held: Boolean)
private data class SelectorCrossPhase(val release: SelectorCrossReturn, val regrab: Boolean)

/** Final geometry is recorded after composition; ordinary held warp never waits on another clock. */
@Composable
internal fun rememberSelectorCrossFrame(raw: SelectorCrossFrame, rest: SelectorCrossFrame,
    held: Boolean, bodySettled: Boolean, maximumCorner: Float, maximumOuterCorner: Float,
    enabled: Boolean = true): SelectorCrossFrame {
    val applied = remember { AppliedSelectorCross(raw, rest, held) }
    var phase by remember { mutableStateOf<SelectorCrossPhase?>(null) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(LocalMotionSpeed.current)
    val settled by rememberUpdatedState(bodySettled)
    val reduced = LocalReducedMotion.current || !enabled
    val resized = applied.rest != rest
    val changedContact = applied.held != held
    val interrupted = phase != null
    val captured = applied.frame
    val proposed = when {
        reduced || resized -> raw
        changedContact && (!held || interrupted || captured != raw) -> captured
        phase?.regrab == true -> raw.toward(phase!!.release.start, phase!!.release.progress(elapsed))
        phase != null -> phase!!.release.frame(elapsed)
        else -> raw
    }
    val output = proposed.copy(corner = minOf(proposed.corner, maximumCorner, proposed.lens / 2f),
        outerCorner = minOf(proposed.outerCorner, maximumOuterCorner, proposed.shell / 2f))
    SideEffect { applied.frame = output; applied.rest = rest; applied.held = held }
    LaunchedEffect(held, rest, reduced) {
        if (reduced || resized || !changedContact) { phase = null; return@LaunchedEffect }
        if (held && !interrupted && captured == raw) { phase = null; return@LaunchedEffect }
        // A new hold during ordinary travel also bridges from its displayed contour.
        // Cross velocity is dissipated at the release/re-grab boundary. Axial momentum
        // and optical depth retain their existing uninterrupted GlassBodyState clocks.
        val release = SelectorCrossReturn(captured, if (held) raw else rest)
        elapsed = 0f
        phase = SelectorCrossPhase(release, regrab = held)
        var previousTime = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            elapsed += ((now - previousTime) / 1e9f).coerceIn(.001f, .1f) * speed
            previousTime = now
        } while (release.progress(elapsed) >= .00001f || (!held && !settled))
        phase = null
    }
    return output
}
