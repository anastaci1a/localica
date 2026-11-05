package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** One transaction's frame-driven value, shared by layout and drawing. */
@Stable
internal class BrowsePinchProgress(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    private val mutations = MutatorMutex()
    private var fingerTarget = initial

    fun follow(target: Float) { fingerTarget = target }

    suspend fun snapTo(target: Float) = mutations.mutate { value = target }

    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) = mutations.mutate {
        val owner = currentCoroutineContext().job
        animate(value, target, animationSpec = spec) { frame, _ -> if (owner.isActive) value = frame }
    }

    suspend fun catchUp(onCaughtUp: () -> Unit) = mutations.mutate {
        val from = value
        // Touch tracking keeps its 100ms clock regardless of system animation speed.
        withContext(PinchTrackingDurationScale) {
            val owner = currentCoroutineContext().job
            animate(0f, 1f, animationSpec = tween(PinchCatchUpMillis.toInt(), easing = FastOutSlowInEasing)) { fraction, _ ->
                if (owner.isActive) {
                    if (fraction >= 1f) onCaughtUp()
                    // Write directly in the animation callback, before layout consumes this frame.
                    value = from + (fingerTarget - from) * fraction
                }
            }
        }
    }
}

private object PinchTrackingDurationScale : MotionDurationScale {
    override val scaleFactor = 1f
}
