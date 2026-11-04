package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/**
 * Eases a change of the player's light/dark polarity over the lyrics open/close animation
 * instead of switching it in one frame. The polarity is a function of the lyrics mode
 * visibility (the same clock that moves the lyrics layout): when the chosen ink changes while
 * that animation is between its endpoints, the displayed polarity interpolates from where it was to
 * the new one as the visibility travels from its value at the change to its endpoint. Outside a
 * lyrics animation a changed ink applies immediately, as before.
 *
 * One fraction drives both the foreground ([Step.shown], the ink colour) and the material
 * ([Step.light], 0 for dark ink and 1 for light ink, which feeds glass tint, frost and shading), so the
 * two can never disagree or start at different times.
 */
internal class PlayerInkTransition(initial: Color) {
    /** What one resolution leaves behind; applying it is separate so composition never mutates. */
    internal data class Step(val shown: Color, val target: Color, val start: Color, val startProgress: Float, val lastEnd: Float,
        val light: Float = lightOf(shown), val startLight: Float = light)

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
        val targetLight = lightOf(inkTarget)
        if (!animating) return Step(inkTarget, inkTarget, inkTarget, s.startProgress, end, targetLight, targetLight)
        var start = s.start; var startLight = s.startLight; var startProgress = s.startProgress; var target = s.target
        if (inkTarget != target) {
            start = s.shown; startLight = s.light; startProgress = progress; target = inkTarget
        } else if (s.lastEnd != end && s.shown != target) {
            // The animation reversed: continue from the displayed ink rather than jumping.
            start = s.shown; startLight = s.light; startProgress = progress
        }
        val fraction = ink(progress, end, startProgress)
        return Step(lerp(start, target, fraction), target, start, startProgress, end,
            startLight + (targetLight - startLight) * fraction, startLight)
    }

    fun commit(step: Step) { committed = step }

    /** Resolve and commit in one call, for callers outside composition. */
    fun update(inkTarget: Color, progress: Float, open: Boolean): Color =
        resolve(inkTarget, progress, open).also(::commit).shown

    private fun ink(progress: Float, end: Float, startProgress: Float): Float {
        val span = end - startProgress
        return if (abs(span) < SETTLED) 1f else ((progress - startProgress) / span).coerceIn(0f, 1f)
    }

    internal companion object {
        const val SETTLED = 1e-3f
        /** Light ink is white-ish: the polarity a colour stands for. */
        fun lightOf(ink: Color) = if (ink.luminance() > .5f) 1f else 0f
    }
}
