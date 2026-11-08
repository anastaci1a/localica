package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

/** The most animation time the first displayed frame may consume. A slow frame (the destination's
 * first composition, a release's recomposition) otherwise finished an ease in one step (#560). */
internal const val PinchFrameBudgetNanos = 1_000_000_000L / 60

/** Later frames keep an ease on screen for at least this many displayed frames (#641 train × #560, PinchSlowFrameTest). */
internal const val PinchEaseMinimumFrames = 5

/** Immutable admitted samples stamped in exact frame nanos. The initial sample stands for every stamp before the first
 * admitted frame (a frame may legitimately carry a vsync stamp older than the intent that created the playback). Reads select
 * the knot at or before a stamp; they never advance playback. */
internal class PresentedFrameKnots(initial: Kinematic) {
    private val nanos = arrayListOf<Long>()
    private val samples = arrayListOf(initial)
    val lastNanos: Long? get() = nanos.lastOrNull()
    val last: Kinematic get() = samples.last()
    fun append(stampNanos: Long, sample: Kinematic) {
        val previous = nanos.lastOrNull()
        require(previous == null || stampNanos >= previous) { "Knot stamp $stampNanos precedes the last admitted frame $previous" }
        require(sample.value.isFinite() && sample.velocity.isFinite()) { "Non-finite presented sample $sample at $stampNanos" }
        if (previous == stampNanos) samples[samples.lastIndex] = sample else { nanos += stampNanos; samples += sample }
    }
    /** The sample of the last admitted frame at or before [stampNanos], or the initial sample. */
    fun at(stampNanos: Long): Kinematic {
        var left = 0
        var right = nanos.size
        while (left < right) { val middle = (left + right) / 2; if (nanos[middle] <= stampNanos) left = middle + 1 else right = middle }
        return samples[left]
    }
}

/** Frame-admitted played time. The two legacy laws stay distinct; [prepareFrame] runs before the root awaits the frame. */
internal sealed class PlayedTimeLaw(val durationNanos: Long) {
    open fun prepareFrame() {}
    /** Played nanoseconds for this admitted frame, monotone and saturating at [durationNanos]. */
    abstract fun admit(stamp: FrameStamp): Long

    /** pinchFrames: the first admitted frame plays zero (a zero scale or duration finishes at once); each later frame adds its
     * real interval, capped at the first then the later budget, divided by the scale captured at transaction start, truncated. */
    class Pinch(durationNanos: Long, private val scale: Float, private val firstBudget: Long, private val laterBudget: Long) : PlayedTimeLaw(durationNanos) {
        private var previous = Long.MIN_VALUE
        private var intervals = 0
        private var played = 0L
        override fun admit(stamp: FrameStamp): Long {
            val before = previous
            previous = stamp.nanos
            if (scale <= 0f || durationNanos <= 0L) { played = durationNanos; return played }
            if (before == Long.MIN_VALUE) return 0L
            val budget = if (intervals++ == 0) firstBudget else laterBudget
            played = minOf(durationNanos, played + ((stamp.nanos - before).coerceIn(0L, budget) / scale).toLong())
            return played
        }
    }

    /** Compose SuspendAnimation: the platform scale is captured before each pending frame; the first admitted frame is the
     * origin and plays zero (a zero scale plays the whole duration); later frames play absolute elapsed over that scale. */
    class Platform(durationNanos: Long, private val scale: () -> Float) : PlayedTimeLaw(durationNanos) {
        private var origin = Long.MIN_VALUE
        private var pending = captured()
        private fun captured() = scale().also { require(it.isFinite() && it >= 0f) { "Platform duration scale must be finite and non-negative" } }
        override fun prepareFrame() { pending = captured() }
        override fun admit(stamp: FrameStamp): Long {
            if (origin == Long.MIN_VALUE) { origin = stamp.nanos; return if (pending == 0f) durationNanos else 0L }
            return if (pending == 0f) durationNanos else minOf(durationNanos, ((stamp.nanos - origin) / pending).toLong())
        }
    }
}

/** Legacy pinch channels never follow root access: callers already snap under app Reduced Motion and the platform scale is
 * captured per transaction. The owner admits only its own playback family. */
internal data object PinchProgressOwner : MotionOwner {
    override val followsRoot: Boolean get() = false
    override fun admitsReduced(trajectory: Trajectory, spec: UiSpec) = trajectory is FramePlayback && trajectory.owner === this
}

/** Scrollbar release and idle fade follow the platform scale only, as the legacy Animatables did. */
internal data object ScrollbarMotionOwner : MotionOwner {
    override val followsRoot: Boolean get() = false
    override fun admitsReduced(trajectory: Trajectory, spec: UiSpec) = trajectory is FramePlayback && trajectory.owner === this
}

/** One confined Compose animation played by the root clock. A playback belongs to exactly one intent of one MotionFloat
 * (fresh object = fresh root transaction token). Presented values are knots recorded at admitted frames: between frames
 * the latest knot holds, historical reads use exact stamps and nothing here advances played time.
 *
 * Velocity is the real-time derivative of the presented value: the animation's velocity per played second, times the
 * played/real ratio of the admitted interval, times the live span for catch-up. The first admitted frame plays zero and
 * reports zero; a finished knot reports zero because the value holds from that frame on (the Compose reference resets
 * its velocity vector when an animation ends). */
internal class FramePlayback(
    internal val owner: MotionOwner,
    private val law: PlayedTimeLaw,
    private val animation: TargetBasedAnimation<Float, AnimationVector1D>,
    startNanos: Long,
    initial: Float,
    /** Catch-up only: the live finger target, read once per admitted frame AFTER the completion callback has run. */
    private val liveTarget: (() -> Float)? = null,
    private val from: Float = initial,
    private val onComplete: (() -> Unit)? = null,
) : Trajectory {
    override val kind = 1
    override val duration = law.durationNanos / 1e9
    private val start = startNanos
    private val knots = PresentedFrameKnots(Kinematic(initial, 0f))
    private var playedNanos = 0L
    private var lastTarget = from
    var detached = false; private set
    var finished = false; private set
    var endpoint = initial; private set
    /** No root transaction token: a playback carries its own preparation (the scale captured at construction and before each
     * prepared frame), so an intent committed while the root already awaits a frame is sampled and may complete at that very
     * frame, as the legacy Animatable and pinchFrames did. A replacement is a new playback on a new channel and can never
     * consume this one's preparation; work created inside a frame callback still starts on the next frame. */
    val transaction: Any? get() = null

    /** A superseded playback keeps no live callbacks or target reads but still plays out to its Rest. */
    fun detach() { detached = true }
    fun prepareFrame() = law.prepareFrame()

    /** [stillCurrent] is rechecked after the completion callback, which may retarget, cancel, dispose or replace the owner. */
    fun sampleFrame(stamp: FrameStamp, stillCurrent: () -> Boolean) {
        if (finished) return
        val previousNanos = knots.lastNanos
        val previousPlayed = playedNanos
        val played = law.admit(stamp)
        playedNanos = played
        val complete = played >= law.durationNanos
        if (complete && !detached) onComplete?.invoke()
        if (!stillCurrent()) return
        val target = if (liveTarget == null) null else (if (detached) lastTarget else liveTarget.invoke().also { lastTarget = it })
        val fraction = animation.getValueFromNanos(played)
        val value = if (target == null) fraction else from + (target - from) * fraction
        val interval = if (previousNanos == null) 0L else stamp.nanos - previousNanos
        val ratio = if (interval > 0L) (played - previousPlayed).toDouble() / interval else 0.0
        val span = if (target == null) 1.0 else (target - from).toDouble()
        // A finished knot is at rest from this frame on; the Compose reference also resets its velocity when an animation ends.
        val velocity = if (complete) 0f else (animation.getVelocityVectorFromNanos(played).value * span * ratio).toFloat()
        knots.append(stamp.nanos, Kinematic(value, velocity))
        if (complete) { finished = true; endpoint = value }
    }
    internal fun valueAt(stampNanos: Long) = knots.at(stampNanos).value.toDouble()
    internal fun velocityAt(stampNanos: Long) = knots.at(stampNanos).velocity.toDouble()
    override fun value(t: Double) = valueAt(start + (t * 1e9).toLong())
    override fun velocity(t: Double) = velocityAt(start + (t * 1e9).toLong())
}

private fun EasingSpec.compose() = CubicBezierEasing(x1, y1, x2, y2)

/** Retires a channel exactly once: a settled channel now, an unsettled one through its own onRest. */
private fun MotionFloat.retireAtRest() { if (settled) dispose() else onRest = { onRest = null; dispose() } }

/** One pinch transaction's presented progress. Every real intent is a fresh registered MotionFloat born at the presented
 * value; the superseded channel is detached and plays out to its Rest unobserved, so the visible value freezes exactly
 * where the legacy MutatorMutex cancellation left it and no old transaction can write the next owner's value. The root
 * clock is resolved from the calling coroutine's frame clock on the first suspend mutation and may not change. */
@Stable internal class PinchProgressMotion(private val initial: Float) {
    private var clock: UiClock? = null
    private var channel by mutableStateOf<MotionFloat?>(null)
    private val mutations = MutatorMutex()
    private var fingerTarget = initial
    private var closed = false

    fun value(): Float = channel?.value() ?: initial
    /** The presented channel (tests and diagnostics); null before the first intent. */
    internal val current: MotionFloat? get() = channel
    fun follow(target: Float) { fingerTarget = target }

    private suspend fun root(): UiClock {
        val resolved = registeredRootClock()
        val bound = clock
        if (bound == null) clock = resolved else check(bound === resolved) { "A pinch progress is bound to one root clock" }
        check(!closed) { "Retired pinch progress" }
        return resolved
    }
    /** Freezes the presented value into a fresh Rest channel and lets the superseded channel retire through its own Rest. */
    private fun supersede(clock: UiClock, value: Float = value()): Float {
        channel?.let { previous -> previous.playback?.detach(); previous.retireAtRest() }
        channel = MotionFloat(clock, value, 1f, owner = PinchProgressOwner)
        return value
    }
    private suspend fun play(clock: UiClock, from: Float, destination: Float, identity: Any?, build: (MotionFloat) -> FramePlayback) {
        val motion = MotionFloat(clock, from, 1f, owner = PinchProgressOwner)
        channel?.let { previous -> previous.playback?.detach(); previous.retireAtRest() }
        channel = motion
        val stamp = clock.stampNow
        val playback = build(motion)
        motion.commit(motion.prepareSegment(playback, destination, stamp, identity))
        try {
            snapshotFlow { motion.epoch.intValue; motion.settled }.first { it }
        } finally {
            if (channel === motion && !playback.finished && !closed) supersede(clock, motion.value())
        }
    }

    suspend fun snapTo(target: Float) = mutations.mutate { supersede(root(), target) }

    /** Honors the system animation scale captured at transaction start; advances at most one frame budget per frame. */
    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) = mutations.mutate {
        val clock = root()
        val from = value()
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, target)
        play(clock, from, target, spec) {
            FramePlayback(PinchProgressOwner, PlayedTimeLaw.Pinch(animation.durationNanos, scale, PinchFrameBudgetNanos,
                maxOf(PinchFrameBudgetNanos, animation.durationNanos / PinchEaseMinimumFrames)), animation, clock.stampNow.nanos, from)
        }
    }

    /** Touch tracking keeps its validated catch-up clock regardless of system animation speed; the live finger target is
     * read on every admitted frame after the completion callback, exactly as the legacy frame callback did. */
    suspend fun catchUp(onCaughtUp: () -> Unit) = mutations.mutate {
        val clock = root()
        val from = value()
        val preset = clock.spec.motion.getValue(MotionProfile.PinchCatchUp) as MotionProfileSpec
        val ease = TargetBasedAnimation(tween<Float>(preset.durationMs, easing = preset.easing.compose()), Float.VectorConverter, 0f, 1f)
        play(clock, from, from, preset) {
            FramePlayback(PinchProgressOwner, PlayedTimeLaw.Pinch(ease.durationNanos, 1f, PinchFrameBudgetNanos,
                maxOf(PinchFrameBudgetNanos, ease.durationNanos / PinchEaseMinimumFrames)), ease, clock.stampNow.nanos, from,
                liveTarget = { fingerTarget }, from = from, onComplete = onCaughtUp)
        }
    }

    /** A retired transaction keeps its terminal value; its channels retire through their own Rest. */
    fun retire() {
        if (closed) return
        closed = true
        channel?.let { previous -> previous.playback?.detach(); previous.retireAtRest() }
    }
}

/** Scrollbar release gap and idle opacity as registered channels; both follow the platform scale only (legacy Animatables). */
@Stable internal class ScrollbarMotion(private val clock: UiClock, private val platformScale: () -> Float) {
    private var release by mutableStateOf<MotionFloat?>(null)
    private var opacity by mutableStateOf<MotionFloat?>(null)
    private var disposed = false

    /** The thumb's displayed fraction: the finger while dragging, else the true position plus the easing release gap. */
    fun displayed(fraction: Float, dragging: Boolean, dragFraction: Float): Float =
        if (dragging) dragFraction else (fraction + (release?.value() ?: 0f)).coerceIn(0f, 1f)
    fun opacity(): Float = opacity?.value() ?: 1f
    /** The raw release gap (tests and diagnostics). */
    internal fun releaseValue(): Float = release?.value() ?: 0f

    private fun start(previous: MotionFloat?, initial: Float, destination: Float, identity: Any, animation: TargetBasedAnimation<Float, AnimationVector1D>): MotionFloat {
        clock.checkMutation()
        check(!disposed)
        previous?.let { it.playback?.detach(); it.retireAtRest() }
        val motion = MotionFloat(clock, initial, 1f, owner = ScrollbarMotionOwner)
        val stamp = clock.stampNow
        motion.commit(motion.prepareSegment(FramePlayback(ScrollbarMotionOwner, PlayedTimeLaw.Platform(animation.durationNanos, platformScale),
            animation, stamp.nanos, initial), destination, stamp, identity))
        motion.retireAtRest()
        return motion
    }
    /** After a drag the thumb eases from the finger to the true position: snap to the gap, then the release preset to zero. */
    fun release(gap: Float) {
        val preset = clock.spec.motion.getValue(MotionProfile.ScrollbarRelease) as MotionProfileSpec
        val value = gap.coerceIn(-1f, 1f)
        release = start(release, value, 0f, preset, TargetBasedAnimation(tween(preset.durationMs, easing = preset.easing.compose()), Float.VectorConverter, value, 0f))
    }
    /** Movement or contact shows the bar at once; the delayed fade runs on the frame clock. */
    fun wake() {
        val preset = clock.spec.motion.getValue(MotionProfile.ScrollbarIdleFade) as DelayedFadeSpec
        opacity = start(opacity, 1f, 0f, preset, TargetBasedAnimation(tween(preset.fadeMs, preset.delayMs, preset.easing.compose()), Float.VectorConverter, 1f, 0f))
    }
    fun dispose() {
        clock.checkMutation()
        if (disposed) return
        disposed = true
        release?.let { it.playback?.detach(); it.retireAtRest() }
        opacity?.let { it.playback?.detach(); it.retireAtRest() }
    }
}

@Composable internal fun rememberScrollbarMotion(): ScrollbarMotion {
    val clock = ownerClock()
    val scope = rememberCoroutineScope()
    val owner = remember(clock) { ScrollbarMotion(clock) { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f } }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}

/** The two legacy scrollbar layers (thumb and preview): presentation bound inside the Engine, read in the draw phase. */
internal fun Modifier.scrollbarLayer(opacity: () -> Float, translationY: () -> Float, scale: (() -> Float)? = null, origin: TransformOrigin? = null): Modifier =
    graphicsLayer {
        alpha = opacity()
        this.translationY = translationY()
        if (scale != null) { val s = scale(); scaleX = s; scaleY = s }
        if (origin != null) transformOrigin = origin
    }
