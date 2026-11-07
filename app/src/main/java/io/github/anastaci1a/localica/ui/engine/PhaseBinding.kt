package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/** U08.02a shared-phase binding: a polarity follows its owner's phase to that phase's end. A changed target, a reversal or
 * a restarted/replaced clock rejoins from the shown value and its slope per unit of travel (C1), never a clamped remainder. */
internal class PhaseBinding(initial: Float) {
    @Immutable data class Step(val value: Float, val slope: Float, val target: Float, val from: Float, val fromSlope: Float,
        val start: Float, val end: Float, val clock: Int, val last: Float) {
        internal fun at(travel: Float, d: Boolean) = hermite(from.toDouble(), fromSlope.toDouble(), target.toDouble(), 0.0,
            abs(end - start).toDouble(), travel.toDouble(), d).toFloat()
    }
    private var committed = Step(initial, 0f, initial, initial, 0f, 0f, 0f, 0, 0f)

    /** Pure, so composition may call it; [phase] travels toward [end]. Publish the result with [present]. */
    fun resolve(target: Float, phase: Float, end: Float, clock: Int = 0): Step {
        val s = committed
        if (abs(phase - end) <= SETTLED) return Step(target, 0f, target, target, 0f, phase, end, clock, phase)
        val rejoin = target != s.target || s.value != target && (s.end != end || s.clock != clock ||
            (if (end > .5f) phase < s.last - SETTLED else phase > s.last + SETTLED))
        // The same clock rejoins its own curve at this phase, never retracing it; another clock, the last shown frame.
        val travel = maxOf(abs(phase - s.start), abs(s.last - s.start)); val same = s.clock == clock
        val next = if (!rejoin) s else s.copy(target = target, from = if (same) s.at(travel, false).coerceIn(0f, 1f) else s.value,
            fromSlope = if (same) s.at(travel, true) else s.slope, start = phase, end = end, clock = clock)
        return next.copy(value = next.at(abs(phase - next.start), false).coerceIn(0f, 1f), slope = next.at(abs(phase - next.start), true), last = phase)
    }
    fun present(step: Step) { committed = step }
    companion object { const val SETTLED = 1e-3f }
}
