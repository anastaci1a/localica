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
    private var target = initial
    private var start = initial
    private var startProgress = 0f
    private var lastEnd = Float.NaN
    private var shown = initial

    /** [progress] is the lyrics mode visibility (0 closed, 1 open); [open] is the mode being animated toward. */
    fun update(inkTarget: Color, progress: Float, open: Boolean): Color {
        val end = if (open) 1f else 0f
        val animating = abs(progress - end) > SETTLED
        if (!animating) {
            target = inkTarget; start = inkTarget; shown = inkTarget; lastEnd = end
            return shown
        }
        if (inkTarget != target) {
            start = shown; startProgress = progress; target = inkTarget
        } else if (lastEnd != end && shown != target) {
            // The animation reversed: continue from the displayed ink rather than jumping.
            start = shown; startProgress = progress
        }
        lastEnd = end
        shown = lerp(start, target, ink(progress, end))
        return shown
    }

    private fun ink(progress: Float, end: Float): Float {
        val span = end - startProgress
        return if (abs(span) < SETTLED) 1f else ((progress - startProgress) / span).coerceIn(0f, 1f)
    }

    private companion object { const val SETTLED = 1e-3f }
}
