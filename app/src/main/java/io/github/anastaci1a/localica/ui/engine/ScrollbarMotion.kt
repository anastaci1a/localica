package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorizedTweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec

/** An incoming tween evaluator driven only by registered shared Engine frames.
 * Fresh work admits time zero on the next frame, as Animatable did. An interrupted grip
 * rejoins at its last presented frame, retaining the incoming evaluator and velocity.
 */
internal class ScrollbarTween(private val clock: UiClock, initial: Float) : ActiveMotion {
    private var resting by mutableFloatStateOf(initial)
    private var animation by mutableStateOf<VectorizedTweenSpec<AnimationVector1D>?>(null)
    private var initialVector = AnimationVector1D(initial)
    private var targetVector = AnimationVector1D(initial)
    private var velocityVector = AnimationVector1D(0f)
    private var durationNanos = 0L
    private var awaiting by mutableStateOf(false)
    private var started = clock.stampNow
    private var disposed = false
    var target: Float = initial
        private set
    override val id = clock.allocateId()
    override var presented = false
        private set
    override val settled: Boolean get() = animation == null
    private fun elapsed(stamp: FrameStamp) = if (awaiting) 0L else (stamp.nanos - started.nanos).coerceAtLeast(0L)
    fun value(): Float {
        presented = true
        val active = animation ?: return resting
        val elapsed = elapsed(clock.stamp)
        return if (elapsed >= durationNanos) target else
            active.getValueFromNanos(elapsed, initialVector, targetVector, velocityVector).value
    }
    private fun velocity(): Float = animation?.getVelocityFromNanos(elapsed(clock.stamp), initialVector, targetVector, velocityVector)?.value ?: 0f
    fun start(from: Float, to: Float, duration: Int, delay: Int = 0,
        easing: Easing = FastOutSlowInEasing, sequential: Boolean = false, reduced: Boolean = false) {
        clock.checkMutation()
        check(!disposed)
        val running = !settled
        val velocity = if (sequential && running) velocity() else 0f
        target = to
        resting = from
        if (reduced) {
            cancel(to)
            return
        }
        val evaluator = tween<Float>(duration, delay, easing).vectorize(Float.VectorConverter)
        initialVector = AnimationVector1D(from)
        targetVector = AnimationVector1D(to)
        velocityVector = AnimationVector1D(velocity)
        durationNanos = evaluator.getDurationNanos(initialVector, targetVector, velocityVector)
        // Warm the evaluator's reusable result vectors at intent time, never on a presented read.
        evaluator.getValueFromNanos(0L, initialVector, targetVector, velocityVector)
        evaluator.getVelocityFromNanos(0L, initialVector, targetVector, velocityVector)
        animation = evaluator
        awaiting = !sequential || !running
        started = clock.stamp
        clock.register(this)
    }
    override fun endsAt(stamp: FrameStamp) = awaiting || animation != null && elapsed(stamp) >= durationNanos
    override fun finish(stamp: FrameStamp) {
        clock.checkMutation()
        if (awaiting) {
            awaiting = false
            started = stamp
            clock.register(this)
        } else cancel(target)
    }
    override fun reduce(stamp: FrameStamp) = cancel(target)
    fun cancel(value: Float) {
        clock.checkMutation()
        clock.unregister(this)
        animation = null
        awaiting = false
        resting = value
        target = value
    }
    fun dispose(value: Float) {
        cancel(value)
        disposed = true
    }
}

/** One component owns reveal, grip and release, with separate registered motion channels.
 * Entering Reduced retires every channel immediately and never replays stale activity.
 */
@Stable
internal class ScrollbarMotion(private val clock: UiClock, initiallyHeld: Boolean = false,
    private var reduced: Boolean = false) {
    private val release = ScrollbarTween(clock, 0f)
    private val opacity = ScrollbarTween(clock, 1f)
    private val grip = ScrollbarTween(clock, if (initiallyHeld) ScrollbarSpec.heldScale else 1f)
    private var disposed = false
    fun scale() = grip.value()
    fun releaseOffset() = release.value()
    internal fun opacity() = opacity.value()
    fun reveal() {
        check(!disposed)
        opacity.start(1f, 0f, ScrollbarSpec.fadeMillis, ScrollbarSpec.idleMillis, reduced = reduced)
    }
    fun release(gap: Float) {
        check(!disposed)
        release.start(gap, 0f, ScrollbarSpec.releaseMillis, reduced = reduced)
    }
    fun contact(held: Boolean, speed: Float) {
        check(!disposed)
        val target = if (held) ScrollbarSpec.heldScale else 1f
        if (target == grip.target) return
        grip.start(grip.value(), target, if (reduced) 0 else (ScrollbarSpec.gripMillis / speed).toInt(),
            easing = CubicBezierEasing(0f, 0f, .2f, 1f), sequential = true, reduced = reduced)
    }
    fun access(reduced: Boolean) {
        clock.checkMutation()
        if (reduced && !this.reduced) {
            val stamp = clock.stampNow
            release.reduce(stamp)
            opacity.reduce(stamp)
            grip.reduce(stamp)
        }
        this.reduced = reduced
    }
    fun cancel() {
        release.cancel(0f)
        opacity.cancel(0f)
        grip.cancel(1f)
    }
    fun dispose() {
        release.dispose(0f)
        opacity.dispose(0f)
        grip.dispose(1f)
        disposed = true
    }
}

@Composable
internal fun rememberScrollbarMotion(key: Any, movement: () -> Pair<Int, Int>, activity: () -> Int,
    held: Boolean, speed: Float, reduced: Boolean): ScrollbarMotion {
    val clock = LocalUiClock.current
    val owner = remember(clock) { ScrollbarMotion(clock, held, reduced) }
    val latestMovement by rememberUpdatedState(movement)
    val latestActivity by rememberUpdatedState(activity)
    SideEffect { owner.access(reduced); owner.contact(held, speed) }
    LaunchedEffect(owner, key) {
        snapshotFlow { latestMovement() to latestActivity() }.collect { owner.reveal() }
    }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}

/** Both drawings sample one reveal; only the thumb scales about its outer top edge. */
internal fun Modifier.scrollbarPresentation(motion: ScrollbarMotion, position: () -> Float,
    scaleThumb: Boolean): Modifier = graphicsLayer {
    alpha = motion.opacity()
    translationY = position()
    if (scaleThumb) {
        scaleX = motion.scale()
        scaleY = motion.scale()
        transformOrigin = TransformOrigin(1f, 0f)
    }
}
