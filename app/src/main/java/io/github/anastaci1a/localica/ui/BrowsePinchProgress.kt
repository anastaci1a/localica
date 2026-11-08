package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** The most animation time one displayed frame may consume. A slow frame (the destination's
 * first composition, a release's recomposition) otherwise finished an ease in one step (#560). */
internal const val PinchFrameBudgetNanos = 1_000_000_000L / 60

/** One transaction's frame-driven value, shared by layout and drawing. */
@Stable
internal class BrowsePinchProgress(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    private val mutations = MutatorMutex()
    private var fingerTarget = initial

    fun follow(target: Float) { fingerTarget = target }

    suspend fun snapTo(target: Float) = mutations.mutate { value = target }

    /** Honors the system animation scale, but advances at most one frame budget per frame. */
    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) = mutations.mutate {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, value, target)
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        pinchFrames(animation.durationNanos, scale) { played -> value = animation.getValueFromNanos(played) }
    }

    /** Touch tracking keeps its 100ms clock regardless of system animation speed. */
    suspend fun catchUp(onCaughtUp: () -> Unit) = mutations.mutate {
        val from = value
        val ease = TargetBasedAnimation(tween<Float>(PinchCatchUpMillis.toInt(), easing = FastOutSlowInEasing),
            Float.VectorConverter, 0f, 1f)
        pinchFrames(ease.durationNanos, 1f) { played ->
            val fraction = ease.getValueFromNanos(played)
            if (played >= ease.durationNanos) onCaughtUp()
            // Write in the frame callback, before layout consumes this frame.
            value = from + (fingerTarget - from) * fraction
        }
    }
}

/** Calls [frame] with the played time on every frame, starting at zero on the first one.
 * Each frame adds its real interval, capped at [PinchFrameBudgetNanos]. */
internal suspend fun pinchFrames(durationNanos: Long, scale: Float, frame: (Long) -> Unit) {
    val owner = currentCoroutineContext().job
    if (scale <= 0f || durationNanos <= 0L) { withFrameNanos { if (owner.isActive) frame(durationNanos) }; return }
    var previous = withFrameNanos { if (owner.isActive) frame(0L); it }
    var played = 0L
    while (played < durationNanos) {
        previous = withFrameNanos { now ->
            played = minOf(durationNanos, played + ((now - previous).coerceIn(0L, PinchFrameBudgetNanos) / scale).toLong())
            if (owner.isActive) frame(played)
            now
        }
    }
}
