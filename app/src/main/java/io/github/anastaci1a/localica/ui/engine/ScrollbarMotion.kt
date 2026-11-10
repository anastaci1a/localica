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
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec

/** An incoming tween evaluator driven only by registered shared Engine frames.
 * Fresh work admits time zero on the next frame, as Animatable did. An interrupted grip
 * rejoins at its last presented frame, retaining the incoming evaluator and velocity.
 * A completed tween runs its continuation on that Engine frame. Reduction settles at
 * [reducedValue] when one is given, and never runs a continuation.
 */
internal class ScrollbarTween(private val clock: UiClock, initial: Float,
    private val reducedValue: Float? = null) : ActiveMotion {
    private var resting by mutableFloatStateOf(initial)
    private var animation by mutableStateOf<VectorizedTweenSpec<AnimationVector1D>?>(null)
    private var initialVector = AnimationVector1D(initial)
    private var targetVector = AnimationVector1D(initial)
    private var velocityVector = AnimationVector1D(0f)
    private var durationNanos = 0L
    private var delayNanos = 0L
    private var holding by mutableStateOf(false)
    private var awaiting by mutableStateOf(false)
    private var started = clock.stampNow
    private var disposed = false
    private var next: (() -> Unit)? = null
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
        // Delay frames have no visible change. Do not subscribe placement/drawing to their clock.
        if (awaiting || holding) return resting
        val elapsed = elapsed(clock.stamp)
        return if (elapsed >= durationNanos) target else
            active.getValueFromNanos(elapsed, initialVector, targetVector, velocityVector).value
    }
    private fun velocity(): Float = animation?.getVelocityFromNanos(elapsed(clock.stamp), initialVector, targetVector, velocityVector)?.value ?: 0f
    fun start(from: Float, to: Float, duration: Int, delay: Int = 0,
        easing: Easing = FastOutSlowInEasing, sequential: Boolean = false, reduced: Boolean = false,
        then: (() -> Unit)? = null) {
        clock.checkMutation()
        check(!disposed)
        val running = !settled
        val velocity = if (sequential && running) velocity() else 0f
        target = to
        resting = from
        if (reduced) {
            cancel(to)
            then?.invoke()
            return
        }
        val evaluator = tween<Float>(duration, delay, easing).vectorize(Float.VectorConverter)
        initialVector = AnimationVector1D(from)
        targetVector = AnimationVector1D(to)
        velocityVector = AnimationVector1D(velocity)
        durationNanos = evaluator.getDurationNanos(initialVector, targetVector, velocityVector)
        delayNanos = delay * 1_000_000L
        holding = delay > 0
        // Warm the evaluator's reusable result vectors at intent time, never on a presented read.
        evaluator.getValueFromNanos(0L, initialVector, targetVector, velocityVector)
        evaluator.getVelocityFromNanos(0L, initialVector, targetVector, velocityVector)
        animation = evaluator
        next = then
        awaiting = !sequential || !running
        started = clock.stamp
        clock.register(this)
    }
    override fun endsAt(stamp: FrameStamp) = when {
        awaiting -> true
        animation == null -> false
        holding -> elapsed(stamp) >= delayNanos
        else -> elapsed(stamp) >= durationNanos
    }
    override fun finish(stamp: FrameStamp) {
        clock.checkMutation()
        if (awaiting) {
            awaiting = false
            started = stamp
            clock.register(this)
        } else if (holding && elapsed(stamp) < durationNanos) {
            holding = false
            clock.register(this)
        } else {
            val then = next
            cancel(target)
            then?.invoke()
        }
    }
    override fun reduce(stamp: FrameStamp) = cancel(reducedValue ?: target)
    fun cancel(value: Float) {
        clock.checkMutation()
        clock.unregister(this)
        next = null
        animation = null
        awaiting = false
        holding = false
        resting = value
        target = value
    }
    fun dispose(value: Float) {
        cancel(value)
        disposed = true
    }
}

/** One component owns reveal, grip and release, with separate registered motion channels.
 * Activity fades the scrollbar in from its presented opacity. While [hold] is set (a finger on the
 * scrollbar, or a list scroll in progress including its fling) it stays fully visible; the idle
 * delay and the fade out start only once it is released and still. App Reduced changes future grip-target
 * durations only (the grip, and so its colour, snaps); the fades, idle delay and release keep their base
 * timelines. A global Engine reduction retires every channel and hides the scrollbar. The grip channel is one 0..1 progress: the held width, length
 * and colour all derive from it, so they move in lockstep on one timeline and easing.
 */
@Stable
internal class ScrollbarMotion(private val clock: UiClock, initiallyHeld: Boolean = false,
    private var reduced: Boolean = false) {
    private val release = ScrollbarTween(clock, 0f)
    private val opacity = ScrollbarTween(clock, 1f, reducedValue = 0f)
    private val grip = ScrollbarTween(clock, if (initiallyHeld) 1f else 0f)
    private var disposed = false
    private var held = false
    private val afterFadeIn: () -> Unit = ::idle
    /** Held progress: 0 at rest, 1 fully held. */
    fun grip() = grip.value()
    fun releaseOffset() = release.value()
    internal fun opacity() = opacity.value()
    fun reveal() {
        check(!disposed)
        // Activity arrives every frame of a scroll: a fade-in already under way continues as one timeline
        // (restarting it each frame would keep re-entering its slow start). Its end starts the idle delay.
        if (opacity.target == 1f && !opacity.settled) return
        val shown = opacity.value()
        if (shown >= 1f) idle() else opacity.start(shown, 1f, (ScrollbarSpec.fadeMillis * (1f - shown)).toInt(), then = afterFadeIn)
    }
    fun hold(held: Boolean) {
        check(!disposed)
        if (held == this.held) return
        this.held = held
        reveal()
    }
    /** At full opacity: wait out the idle delay, then fade. Nothing is scheduled while held. */
    private fun idle() {
        if (held) { if (opacity.target != 1f) opacity.cancel(1f); return }
        opacity.start(1f, 0f, ScrollbarSpec.fadeMillis, ScrollbarSpec.idleMillis)
    }
    fun release(gap: Float) {
        check(!disposed)
        release.start(gap, 0f, ScrollbarSpec.releaseMillis)
    }
    fun contact(held: Boolean, speed: Float) {
        check(!disposed)
        val target = if (held) 1f else 0f
        if (target == grip.target) return
        grip.start(grip.value(), target, if (reduced) 0 else (ScrollbarSpec.gripMillis / speed).toInt(),
            easing = CubicBezierEasing(0f, 0f, .2f, 1f), sequential = true, reduced = reduced)
    }
    fun access(reduced: Boolean) {
        clock.checkMutation()
        this.reduced = reduced
    }
    fun cancel() {
        release.cancel(0f)
        opacity.cancel(0f)
        grip.cancel(0f)
    }
    fun dispose() {
        release.dispose(0f)
        opacity.dispose(0f)
        grip.dispose(0f)
        disposed = true
    }
}

@Composable
internal fun rememberScrollbarMotion(key: Any, movement: () -> Pair<Int, Int>, activity: () -> Int,
    scrolling: () -> Boolean, held: Boolean, speed: Float, reduced: Boolean): ScrollbarMotion {
    val clock = LocalUiClock.current
    val owner = remember(clock) { ScrollbarMotion(clock, held, reduced) }
    val latestMovement by rememberUpdatedState(movement)
    val latestActivity by rememberUpdatedState(activity)
    val latestScrolling by rememberUpdatedState(scrolling)
    val latestHeld by rememberUpdatedState(held)
    SideEffect { owner.access(reduced); owner.contact(held, speed) }
    LaunchedEffect(owner, key) {
        snapshotFlow { latestMovement() to latestActivity() }.collect { owner.reveal() }
    }
    // A held contact and a list scroll in progress (a finger down, or a fling) keep it fully visible.
    LaunchedEffect(owner, key) {
        snapshotFlow { latestHeld || latestScrolling() }.collect { owner.hold(it) }
    }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}

/** Both drawings sample one reveal and one travel; the thumb sizes and colours itself from [ScrollbarMotion.grip]. */
internal fun Modifier.scrollbarPresentation(motion: ScrollbarMotion, position: () -> Float): Modifier = graphicsLayer {
    alpha = motion.opacity()
    translationY = position()
}
