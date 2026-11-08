package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.flow.first

internal interface SettingsHighlightView : MotionValue { fun ContentDrawScope.draw(tint: Color) }

/** Actual opacity, with the original reveal/hold/fade waveform and an explicitly reduced hold expiry. */
private class SettingsHighlightTrajectory(val preset: SettingsHighlightSpec, val reduced: Boolean) : Trajectory {
    override val kind = 1
    private val durationMs = if (reduced) preset.reducedHoldMs else preset.revealMs + preset.holdMs + preset.fadeMs
    override val duration = durationMs / 1000.0
    override val completionPolicy = if (reduced) "Reduced" else "Continuous"
    private val easing = Bezier(preset.easing)
    private fun resolved(seconds: Double, derivative: Boolean): Double {
        val time = (seconds * 1000).coerceAtLeast(0.0)
        if (time >= durationMs) return 0.0
        if (reduced) return if (derivative) 0.0 else preset.opacity.toDouble()
        val fadeStart = preset.revealMs + preset.holdMs
        return when {
            time < preset.revealMs -> preset.opacity * easing.at(time / preset.revealMs, derivative) *
                if (derivative) 1000.0 / preset.revealMs else 1.0
            time < fadeStart -> if (derivative) 0.0 else preset.opacity.toDouble()
            derivative -> -preset.opacity * easing.at((time - fadeStart) / preset.fadeMs, true) * 1000 / preset.fadeMs
            else -> preset.opacity * (1 - easing.at((time - fadeStart) / preset.fadeMs))
        }
    }
    override fun value(t: Double) = resolved(t, false)
    override fun velocity(t: Double) = resolved(t, true)
}

/** Admission alone is exceptional; a later Normal→Reduced transition still ends this hold. */
private data object SettingsHighlightHoldOwner : MotionOwner {
    override fun admitsReduced(trajectory: Trajectory, spec: UiSpec): Boolean =
        trajectory is SettingsHighlightTrajectory && trajectory.reduced &&
            trajectory.preset == spec.motion.getValue(MotionProfile.SettingsHighlight)
}

/** Every pulse is a registered MotionFloat. A retired view releases its solver only at final Rest. */
internal class SettingsHighlightMotion(private val clock: UiClock) : SettingsHighlightView {
    private class Pulse(val motion: MotionFloat) {
        private var retiring = false
        private var closed = false
        init { motion.onRest = { if (retiring) close() } }
        fun retire() { retiring = true; if (motion.settled) close() }
        private fun close() {
            if (closed) return
            closed = true
            motion.onRest = null
            motion.dispose()
        }
    }
    private var pulse by mutableStateOf<Pulse?>(null)
    private var requested = false
    private var disposed = false
    override val target = 0f
    override val settled get() = pulse?.motion?.settled ?: true
    fun request(shown: Boolean, reduceMotion: Boolean) {
        clock.checkMutation()
        check(!disposed)
        if (shown == requested) return
        requested = shown
        pulse?.retire()
        pulse = null
        if (shown) {
            val preset = clock.spec.motion.getValue(MotionProfile.SettingsHighlight) as SettingsHighlightSpec
            val reduced = reduceMotion || clock.context?.access == MotionAccess.Reduced
            val motion = MotionFloat(clock, if (reduced) preset.opacity else 0f, 1f, 0.0..1.0,
                owner = if (reduced) SettingsHighlightHoldOwner else RootMotionOwner)
            pulse = Pulse(motion)
            motion.commit(motion.prepareSegment(SettingsHighlightTrajectory(preset, reduced), 0f, clock.stampNow, preset,
                policy = if (reduced) "Reduced" else "Continuous"))
        }
    }
    override fun sampleAt(stamp: FrameStamp) = pulse?.motion?.sampleAt(stamp) ?: Kinematic(0f, 0f)
    override fun valueAt(stamp: FrameStamp) = pulse?.motion?.valueAt(stamp) ?: 0f
    override fun velocityAt(stamp: FrameStamp) = pulse?.motion?.velocityAt(stamp) ?: 0f
    override fun sample() = pulse?.motion?.sample() ?: Kinematic(0f, 0f)
    override fun value() = pulse?.motion?.value() ?: 0f
    override fun velocity() = pulse?.motion?.velocity() ?: 0f
    override fun ContentDrawScope.draw(tint: Color) {
        drawContent()
        val opacity = value()
        if (opacity > 0f) drawRect(tint.copy(alpha = opacity))
    }
    fun dispose() {
        clock.checkMutation()
        if (disposed) return
        disposed = true
        pulse?.retire()
        pulse = null
    }
}

@Composable internal fun rememberSettingsHighlight(shown: Boolean, reduced: Boolean): SettingsHighlightView {
    val clock = ownerClock()
    val owner = remember(clock) { SettingsHighlightMotion(clock) }
    SideEffect { owner.request(shown, reduced) }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}

/** The native scroll mutator consumes an Engine trajectory; it never creates a second frame clock. */
internal class SettingsScrollMotion(private val clock: UiClock, private val scroll: ScrollState) {
    suspend fun scrollTo(target: Int) {
        scroll.scroll {
            // Admission can suspend while an old mutator cancels. Read its final position only after acquiring it.
            val initial = scroll.value.toFloat()
            val motion = MotionFloat(clock, initial, 1f)
            try {
                val spec = clock.spec.motion.getValue(MotionProfile.SettingsScroll) as SettingsScrollSpec
                val access = requireNotNull(clock.context).access
                val durationMs = if (access == MotionAccess.Faster) spec.fasterMs else spec.normalMs
                val end = target.toFloat()
                val plan = if (access == MotionAccess.Reduced || initial == end) Rest(end.toDouble())
                    else Eased(initial.toDouble(), 0.0, end.toDouble(), spec.easing, durationMs / 1000.0)
                motion.commit(motion.prepareSegment(plan, end, clock.stampNow, spec,
                    policy = if (access == MotionAccess.Reduced) "Reduced" else "Continuous"))
                var previous = initial
                snapshotFlow { motion.value() to motion.settled }.first { (position, settled) ->
                    previous += scrollBy(position - previous)
                    settled
                }
            } finally {
                if (motion.settled) motion.dispose()
                else motion.onRest = { motion.onRest = null; motion.dispose() }
            }
        }
    }
}

@Composable internal fun rememberSettingsScroll(scroll: ScrollState): SettingsScrollMotion {
    val clock = ownerClock()
    return remember(clock, scroll) { SettingsScrollMotion(clock, scroll) }
}
