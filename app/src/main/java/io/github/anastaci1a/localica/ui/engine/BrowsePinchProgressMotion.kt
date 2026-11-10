package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Exact displayed-frame policy retained from the existing pinch transaction. */
internal const val PinchFrameBudgetNanos = 1_000_000_000L / 60
internal const val PinchEaseMinimumFrames = 5

/** A transaction family binds to its root's clock; an unbound fixture cannot animate. */
internal class BrowsePinchProgressOwner {
    private var clock: UiClock? = null

    internal fun bind(value: UiClock) {
        value.checkMutation()
        check(clock == null || clock === value) { "A pinch transaction family keeps its root clock" }
        clock = value
    }

    suspend fun settle(from: Float, target: Float, frame: (Float) -> Unit) =
        animate(from, target, tween(220), frame)

    internal suspend fun animate(from: Float, target: Float, spec: AnimationSpec<Float>, frame: (Float) -> Unit) {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, target)
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        play(animation.durationNanos, scale) { played -> frame(animation.getValueFromNanos(played)) }
    }

    suspend fun catchUp(durationMillis: Int, frame: (Float, Boolean) -> Unit) {
        val animation = TargetBasedAnimation(tween<Float>(durationMillis, easing = FastOutSlowInEasing),
            Float.VectorConverter, 0f, 1f)
        play(animation.durationNanos, 1f) { played ->
            frame(animation.getValueFromNanos(played), played >= animation.durationNanos)
        }
    }

    private suspend fun play(durationNanos: Long, scale: Float, frame: (Long) -> Unit) {
        val root = checkNotNull(clock) { "Bind pinch progress to the shared root UiClock before animation" }
        root.checkMutation()
        val intent = BrowsePinchProgressIntent(root.allocateId(), currentCoroutineContext().job,
            durationNanos, scale, frame)
        root.register(intent)
        try {
            intent.completed.await()
        } finally {
            root.unregister(intent)
        }
    }
}

/** Binds existing transaction handles without creating an independent source of frames. */
@Composable
internal fun BindBrowsePinchProgressClock(owner: BrowsePinchProgressOwner) {
    val clock = ownerClock()
    SideEffect { owner.bind(clock) }
}

/** One suspended intent advances only inside the root's presentation callback. */
private class BrowsePinchProgressIntent(
    override val id: Long,
    private val caller: Job,
    private val durationNanos: Long,
    private val scale: Float,
    private val emit: (Long) -> Unit,
) : ActiveMotion {
    val completed = CompletableDeferred<Unit>()
    private var previous: Long? = null
    private var played = 0L
    private var budget = PinchFrameBudgetNanos
    private val laterBudget = maxOf(PinchFrameBudgetNanos, durationNanos / PinchEaseMinimumFrames)
    private var done = false
    override val presented get() = previous != null
    override val settled get() = done
    override val reducesWithAccess = false

    override fun frame(stamp: FrameStamp) {
        if (!caller.isActive || done) return
        val before = previous
        if (scale <= 0f || durationNanos <= 0L) played = durationNanos
        else if (before != null) {
            played = minOf(durationNanos,
                played + ((stamp.nanos - before).coerceIn(0L, budget) / scale).toLong())
        }
        previous = stamp.nanos
        try {
            // Check cancellation before entry only. A terminal callback may cancel its own
            // transaction before writing the value that this same frame must still present.
            emit(played)
            done = played >= durationNanos
        } catch (failure: Throwable) {
            done = true
            completed.completeExceptionally(failure)
        }
        if (before != null) budget = laterBudget
    }

    override fun endsAt(stamp: FrameStamp) = done || !caller.isActive
    override fun finish(stamp: FrameStamp) { completed.complete(Unit) }
    override fun reduce(stamp: FrameStamp): Unit = error("Pinch timing follows platform duration scale")
}
