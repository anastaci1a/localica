package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.MotionDurationScale
import io.github.anastaci1a.localica.ui.spec.ListMotionSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Retains Foundation's mutation lease and consumed-delta arithmetic on the shared Engine clock. */
internal class SettingsSearchScroll(private val clock: UiClock, private val scroll: ScrollState) {
    suspend fun scrollTo(target: Int, reduced: Boolean, speed: Float) {
        if (reduced) {
            scroll.scrollTo(target)
            return
        }
        val duration = (clock.spec.motion.getValue(MotionProfile.Scroll) as ListMotionSpec).listMs
        // Compute the delta before taking the lease, as ScrollState.animateScrollTo does.
        val animation = TargetBasedAnimation(tween<Float>((duration / speed).toInt()),
            Float.VectorConverter, 0f, (target - scroll.value).toFloat())
        val context = currentCoroutineContext()
        scroll.scroll {
            var consumed = 0f
            val intent = SettingsSearchScrollIntent(clock.allocateId(), context.job, animation,
                scale = { context[MotionDurationScale]?.scaleFactor ?: 1f }) { value ->
                consumed += scrollBy(value - consumed)
            }
            clock.register(intent)
            try {
                intent.completed.await()
            } finally {
                clock.unregister(intent)
            }
        }
    }
}

/** Uses Compose's exact tween evaluator and platform time division, with no independent frame loop. */
internal class SettingsSearchScrollIntent(
    override val id: Long,
    private val caller: Job,
    private val animation: TargetBasedAnimation<Float, androidx.compose.animation.core.AnimationVector1D>,
    private val scale: () -> Float,
    private val emit: (Float) -> Unit,
) : ActiveMotion {
    val completed = CompletableDeferred<Unit>()
    private var start: Long? = null
    private var nextScale = scale()
    private var done = false
    override val presented get() = start != null
    override val settled get() = done
    override val reducesWithAccess = false

    override fun frame(stamp: FrameStamp) {
        if (done || !caller.isActive) return
        val origin = start ?: stamp.nanos.also { start = it }
        try {
            check(nextScale >= 0f)
            val played = if (nextScale == 0f) animation.durationNanos
                else ((stamp.nanos - origin) / nextScale).toLong()
            emit(animation.getValueFromNanos(played))
            done = animation.isFinishedFromNanos(played)
            nextScale = scale()
        } catch (failure: Throwable) {
            done = true
            completed.completeExceptionally(failure)
        }
    }

    override fun endsAt(stamp: FrameStamp) = done || !caller.isActive
    override fun finish(stamp: FrameStamp) { completed.complete(Unit) }
    override fun reduce(stamp: FrameStamp): Unit = error("Settings scroll retains its admitted platform timing")
}
