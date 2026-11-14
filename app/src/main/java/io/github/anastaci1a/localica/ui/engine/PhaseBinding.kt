package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import kotlin.math.abs

/** A destination known at the owner's origin follows its eased phase. Later destinations use the shared value
 * channel until complete. Phase-only bindings rejoin from their shown value and slope per unit of travel (C1). */
internal class PhaseBinding(initial: Float, private val motion: MotionFloat? = null) {
    @Immutable data class Step(val value: Float, val slope: Float, val target: Float, val from: Float, val fromSlope: Float,
        val toSlope: Float, val start: Float, val end: Float, val clock: Int, val last: Float) {
        internal fun at(travel: Float, d: Boolean) = hermite(from.toDouble(), fromSlope.toDouble(), target.toDouble(),
            toSlope.toDouble(), abs(end - start).toDouble(), travel.toDouble(), d).toFloat()
    }
    private var committed = Step(initial, 0f, initial, initial, 0f, 0f, 0f, 0f, 0, 0f)

    /** Pure, so composition may call it; [phase] travels toward [end]. Publish the result with [present]. */
    fun resolve(target: Float, phase: Float, end: Float, clock: Int = 0): Step {
        val s = committed
        // The same clock rejoins its current curve, never retracing it; another clock, the last shown frame.
        val travel = maxOf(abs(phase - s.start), abs(s.last - s.start)); val same = s.clock == clock && abs(s.last - s.end) > SETTLED
        val from = if (same) s.at(travel, false).coerceIn(0f, 1f) else s.value; val slope = if (same) s.at(travel, true) else s.slope
        val timed = motion != null && (motion.kind == 1 || target != s.target && abs(phase - (1f - end)) > SETTLED)
        if (timed || abs(phase - end) <= SETTLED) {
            val value = if (timed && same) from else if (timed || target != s.target || abs(s.last - s.end) <= SETTLED && s.value != target) motion?.value() ?: target else target
            return Step(value, 0f, target, target, 0f, 0f, phase, phase, clock, phase)
        }
        val rejoin = abs(s.last - s.end) <= SETTLED || target != s.target || s.value != target && (s.end != end || s.clock != clock ||
            (if (end > .5f) phase < s.last - SETTLED else phase > s.last + SETTLED))
        val chord = (target - from) / abs(end - phase); val origin = slope == 0f && abs(phase - (1f - end)) <= SETTLED
        val next = if (!rejoin) s else s.copy(target = target, from = from, fromSlope = if (origin) chord else slope,
            toSlope = if (origin) chord else 0f, start = phase, end = end, clock = clock)
        return next.copy(value = next.at(abs(phase - next.start), false).coerceIn(0f, 1f), slope = next.at(abs(phase - next.start), true), last = phase)
    }
    fun present(step: Step) {
        motion?.let {
            if (step.value == step.target && it.kind != 1) it.snapTo(step.target)
            else if (abs(step.last - step.end) <= SETTLED) {
                if (it.kind == 3) it.drive(step.value, it.clock.stampNow)
                it.retarget(step.target, motionProfileId(io.github.anastaci1a.localica.ui.spec.MotionProfile.ArtworkLayout))
            }
            else it.drive(step.value, it.clock.stampNow)
        }
        committed = step
    }
    companion object { const val SETTLED = 1e-3f }
}

@Composable internal fun rememberPhaseBinding(initial: Float): PhaseBinding {
    val motion = rememberMotionFloat("player-ink", initial, 1f, 0.0..1.0)
    return remember(motion) { PhaseBinding(initial, motion) }
}
