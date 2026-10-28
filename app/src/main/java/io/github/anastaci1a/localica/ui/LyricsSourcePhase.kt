package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter

/** Scalar choreography only: this does not prove optical/spatial C1 continuity. */
internal object LyricsSourcePhase {
    const val Handoff = .25f
    const val PreparationEnd = .4f

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun preparation(q: Float) = smooth(q / PreparationEnd)
    fun popup(q: Float) = smooth((q - Handoff) / (1f - Handoff))
}

/** UI-thread confined, retained outside a visibility-keyed coroutine.
 *
 * This owns phase velocity; expansion.snapTo(frame.popup) is an output sink, never
 * a velocity source. Canceling an adapter coroutine does not reset this owner.
 * Use one monotonic clock for every call. Gates represent native ownership safety,
 * not a spring endpoint: Held must never be interpreted as Settled.
 */
internal class LyricsSourcePhaseController {
    enum class Gate { OpeningReady, RestoredDraw }
    enum class Status { Moving, Held, Settled }
    data class Frame(val generation: Long, val q: Float, val velocity: Float, val status: Status,
        val heldGate: Gate? = null, val crossing: Long = 0L) {
        val preparation get() = LyricsSourcePhase.preparation(q)
        val popup get() = LyricsSourcePhase.popup(q)
    }

    private var generation = 0L
    private var crossing = 0L
    private var q = 0f
    private var velocity = 0f
    private var status = Status.Settled
    private var target = 0f
    private var speed = 1f
    private var reduced = false
    private var gate: Gate? = null
    private var startedAt = 0L
    private var sampledAt = 0L
    private var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    val frame get() = Frame(generation, q, velocity, status,
        gate.takeIf { status == Status.Held }, crossing)

    /** Advance old motion first, preserving value and physical units/second velocity.
     * A gate must be installed BEFORE crossing its seam. Re-register an outstanding
     * gate on speed changes; the returned generation replaces any old acknowledgment.
     */
    fun retarget(open: Boolean, reduced: Boolean, speed: Float, nowNanos: Long,
        gate: Gate? = this.gate): Long {
        require(speed.isFinite() && speed > 0f)
        require(nowNanos >= sampledAt)
        advance(generation, nowNanos)
        val keepHeld = status == Status.Held && gate == this.gate
        // Gates constrain ownership, not the requested target. In particular,
        // keep RestoredDraw during a held reopen until the native ack: retained
        // negative velocity could otherwise rejoin the neck before turning.
        require(gate != Gate.OpeningReady || q <= LyricsSourcePhase.Handoff)
        require(gate != Gate.RestoredDraw || q >= LyricsSourcePhase.Handoff)
        generation++
        target = if (open) 1f else 0f
        this.reduced = reduced
        this.speed = speed
        this.gate = gate
        restart(nowNanos)
        if (keepHeld) status = Status.Held
        advance(generation, nowNanos)
        return generation
    }

    /** Acknowledge this crossing, then arm the opposite crossing. No future
     * crossing may be acknowledged early, and duplicate acknowledgments do nothing.
     * Stale native readiness/draw continuations cannot release a newer flight.
     */
    fun release(token: Long, crossing: Long, nowNanos: Long): Boolean {
        if (token != generation || crossing != this.crossing || gate == null || status != Status.Held) return false
        advance(token, nowNanos)
        gate = when (gate) {
            Gate.OpeningReady -> Gate.RestoredDraw
            Gate.RestoredDraw -> Gate.OpeningReady
            null -> error("Held phase requires an ownership gate")
        }
        restart(nowNanos)
        advance(token, nowNanos)
        return true
    }

    /** Explicit cancellation at an unadmitted opening boundary. This is a
     * safety-boundary impulse, NOT velocity preservation: stop outward momentum
     * without changing q, then return closed. Never admit a canceled native modal
     * merely because retained opening velocity reached the boundary.
     * The opening gate stays armed for any later genuine reopening.
     */
    fun rejectOpening(token: Long, crossing: Long, nowNanos: Long): Boolean {
        if (token != generation || crossing != this.crossing ||
            status != Status.Held || gate != Gate.OpeningReady || target != 0f) return false
        advance(token, nowNanos)
        velocity = 0f
        restart(nowNanos)
        advance(token, nowNanos)
        return true
    }

    fun advance(token: Long, nowNanos: Long): Frame {
        if (token != generation) return frame
        require(nowNanos >= sampledAt)
        val previousTime = sampledAt
        sampledAt = nowNanos
        if (status != Status.Moving) return frame
        val motion = requireNotNull(animation)
        val elapsed = nowNanos - startedAt
        val next = if (reduced) target else motion.getValueFromNanos(elapsed)
        val seam = LyricsSourcePhase.Handoff
        var crossingEnd = elapsed
        var furthest = next
        // A critical spring has at most one turning point. A reversal may cross
        // a gate and return within one long frame, so endpoint-only detection
        // would miss the unsafe excursion. Check the extremum as well.
        if (!reduced && gate != null) {
            var low = (previousTime - startedAt).coerceAtLeast(0L)
            var high = elapsed
            fun approaching(time: Long): Boolean {
                val v = motion.getVelocityVectorFromNanos(time).value
                return if (gate == Gate.OpeningReady) v > 0f else v < 0f
            }
            if (approaching(low) && !approaching(high)) {
                while (high - low > 1L) {
                    val middle = low + (high - low) / 2
                    if (approaching(middle)) low = middle else high = middle
                }
                crossingEnd = high
                furthest = motion.getValueFromNanos(high)
            }
        }
        val crossedGate = when (gate) {
            Gate.OpeningReady -> furthest > seam
            Gate.RestoredDraw -> furthest < seam
            null -> false
        }
        if (crossedGate) {
            // Find the crossing time rather than keeping the overshot frame's
            // velocity. No spring settle or extra frame dwell at the seam.
            var low = (previousTime - startedAt).coerceAtLeast(0L)
            var high = crossingEnd
            if (!reduced) {
                while (high - low > 1L) {
                    val middle = low + (high - low) / 2
                    val value = motion.getValueFromNanos(middle)
                    val crossed = if (gate == Gate.OpeningReady) value >= seam else value <= seam
                    if (crossed) high = middle else low = middle
                }
            }
            q = seam
            velocity = if (reduced) 0f else motion.getVelocityVectorFromNanos(high).value
            status = Status.Held
            crossing++
        } else if (reduced || motion.isFinishedFromNanos(elapsed)) {
            q = target
            velocity = 0f
            status = Status.Settled
        } else {
            q = next
            velocity = motion.getVelocityVectorFromNanos(elapsed).value
        }
        return frame
    }

    private fun restart(nowNanos: Long) {
        startedAt = nowNanos
        sampledAt = nowNanos
        animation = TargetBasedAnimation(glassMorphSpring(speed), Float.VectorConverter,
            q, target, AnimationVector1D(velocity))
        status = Status.Moving
    }

    /** Disposal abandons the shape and invalidates every pending continuation. */
    fun reset() {
        generation++
        q = 0f
        velocity = 0f
        target = 0f
        gate = null
        animation = null
        status = Status.Settled
        startedAt = 0L
        sampledAt = 0L
    }
}
