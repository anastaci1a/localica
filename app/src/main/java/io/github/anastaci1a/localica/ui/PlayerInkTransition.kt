package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

/**
 * Eases a change of the player's light/dark foreground over the lyrics open/close animation
 * instead of switching it in one frame. The foreground is a function of the lyrics mode
 * visibility (the same clock that moves the lyrics layout): when the chosen ink changes while
 * that animation is between its endpoints, the displayed ink interpolates from where it was to
 * the new ink as the visibility travels from its value at the change to its endpoint. Outside a
 * lyrics animation a changed ink applies immediately, as before.
 */
internal class PlayerInkTransition(initial: Color) {
    /** What one resolution leaves behind; applying it is separate so composition never mutates. */
    internal data class Step(val shown: Color, val target: Color, val start: Color, val startProgress: Float, val lastEnd: Float)

    private var committed = Step(initial, initial, initial, 0f, Float.NaN)

    /**
     * Pure: derives the ink to show from the last committed step and the current inputs, without
     * changing any state, so it is safe to call during composition. [progress] is the lyrics mode
     * visibility (0 closed, 1 open); [open] is the mode being animated toward. Publish the result
     * with [commit] from a SideEffect.
     */
    fun resolve(inkTarget: Color, progress: Float, open: Boolean): Step {
        val end = if (open) 1f else 0f
        val animating = abs(progress - end) > SETTLED
        val s = committed
        if (!animating) return Step(inkTarget, inkTarget, inkTarget, s.startProgress, end)
        var start = s.start; var startProgress = s.startProgress; var target = s.target
        if (inkTarget != target) {
            start = s.shown; startProgress = progress; target = inkTarget
        } else if (s.lastEnd != end && s.shown != target) {
            // The animation reversed: continue from the displayed ink rather than jumping.
            start = s.shown; startProgress = progress
        }
        return Step(lerp(start, target, ink(progress, end, startProgress)), target, start, startProgress, end)
    }

    fun commit(step: Step) { committed = step }

    /** Resolve and commit in one call, for callers outside composition. */
    fun update(inkTarget: Color, progress: Float, open: Boolean): Color =
        resolve(inkTarget, progress, open).also(::commit).shown

    private fun ink(progress: Float, end: Float, startProgress: Float): Float {
        val span = end - startProgress
        return if (abs(span) < SETTLED) 1f else ((progress - startProgress) / span).coerceIn(0f, 1f)
    }

    private companion object { const val SETTLED = 1e-3f }
}
