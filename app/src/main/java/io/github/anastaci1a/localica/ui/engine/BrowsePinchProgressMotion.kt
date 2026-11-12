package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private var retirementDispatcher: CoroutineDispatcher? = null
    internal val catalog get() = clock?.spec ?: io.github.anastaci1a.localica.ui.spec.StockUiSpec

    internal fun bind(value: UiClock, dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
        value.checkMutation()
        check(clock == null || clock === value) { "A pinch transaction family keeps its root clock" }
        check(retirementDispatcher == null || retirementDispatcher === dispatcher) { "A pinch transaction family keeps its owner dispatcher" }
        clock = value
        retirementDispatcher = dispatcher
    }

    suspend fun settle(from: Float, target: Float, frame: (Float) -> Unit) =
        animate(from, target, tween(220), frame = frame)

    internal suspend fun animate(from: Float, target: Float, spec: AnimationSpec<Float>, budgeted: Boolean = true, frame: (Float) -> Unit) {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, target)
        val access = currentCoroutineContext()[MotionDurationScale]
        val scale = access?.scaleFactor ?: 1f
        play(animation.durationNanos, scale, budgeted, if (budgeted) null else ({ access?.scaleFactor ?: 1f })) { played -> frame(animation.getValueFromNanos(played)) }
    }

    suspend fun catchUp(durationMillis: Int, frame: (Float, Boolean) -> Unit) {
        val animation = TargetBasedAnimation(tween<Float>(durationMillis, easing = FastOutSlowInEasing),
            Float.VectorConverter, 0f, 1f)
        play(animation.durationNanos, 1f) { played ->
            frame(animation.getValueFromNanos(played), played >= animation.durationNanos)
        }
    }

    private suspend fun play(durationNanos: Long, scale: Float, budgeted: Boolean = true, scaleAtFrame: (() -> Float)? = null, frame: (Long) -> Unit) {
        val root = checkNotNull(clock) { "Bind pinch progress to the shared root UiClock before animation" }
        root.checkMutation()
        val intent = BrowsePinchProgressIntent(root.allocateId(), currentCoroutineContext().job,
            durationNanos, scale, frame, budgeted, scaleAtFrame = scaleAtFrame)
        registered(root, intent) { intent.completed.await() }
    }

    internal suspend fun <T> withFrames(block: suspend RootFrames.() -> T): T {
        val root = checkNotNull(clock) { "Bind presentation to the shared root UiClock" }
        root.checkMutation()
        var waiting: kotlinx.coroutines.CancellableContinuation<Long>? = null
        lateinit var intent: BrowsePinchProgressIntent
        intent = BrowsePinchProgressIntent(root.allocateId(), currentCoroutineContext().job,
            Long.MAX_VALUE, 1f, {}, frameStep = { stamp ->
                val listener = waiting
                waiting = null
                root.unregister(intent)
                if (listener?.isActive == true) listener.resume(stamp.nanos)
                false
            })
        val sequence = RootFrames {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                check(waiting == null) { "A root operation awaits one frame at a time" }
                waiting = continuation
                // Install the waiter before waking a source that may return synchronously.
                if (continuation.isActive) root.register(intent) else waiting = null
            }
        }
        try { return registered(root, intent, register = false) { sequence.block() } }
        finally { waiting = null }
    }

    private suspend fun <T> registered(root: UiClock, intent: BrowsePinchProgressIntent, register: Boolean = true, block: suspend () -> T): T {
        if (register) root.register(intent)
        var primaryFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // The caller may resume on a test scheduler or be cancelled away from the
            // owner thread. Retirement must still finish on the bound root owner.
            try {
                withContext(NonCancellable + checkNotNull(retirementDispatcher)) { root.unregister(intent) }
            } catch (retirementFailure: Throwable) {
                val primary = primaryFailure ?: throw retirementFailure
                if (retirementFailure !== primary) primary.addSuppressed(retirementFailure)
            }
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
    private val budgeted: Boolean = true,
    private val frameStep: ((FrameStamp) -> Boolean)? = null,
    private val scaleAtFrame: (() -> Float)? = null,
) : ActiveMotion {
    val completed = CompletableDeferred<Unit>()
    private var previous: Long? = null
    private var origin: Long? = null
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
        if (frameStep != null) {
            previous = stamp.nanos
            try { done = frameStep.invoke(stamp) } catch (failure: Throwable) {
                done = true; completed.completeExceptionally(failure)
            }
            return
        }
        val effectiveScale = scaleAtFrame?.invoke() ?: scale
        if (effectiveScale <= 0f || durationNanos <= 0L) played = durationNanos
        else if (!budgeted) {
            val first = origin ?: stamp.nanos.also { origin = it }
            played = minOf(durationNanos, ((stamp.nanos - first) / effectiveScale).toLong())
        } else if (before != null) {
            played = minOf(durationNanos,
                played + ((stamp.nanos - before).coerceIn(0L, if (budgeted) budget else Long.MAX_VALUE) / scale).toLong())
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


/** A rendezvous admits only frames requested by the live mutation; it never replays stale stamps after a suspension. */
internal class RootFrames internal constructor(private val awaitFrame: suspend () -> Long) {
    suspend fun next(): Long = awaitFrame()
    suspend fun warmLayout() { next(); next() }
}

internal suspend fun BrowsePinchProgressOwner.warmLayout() = withFrames { warmLayout() }

internal suspend fun BrowsePinchProgressOwner.integratePan(step: (Float) -> Boolean) = withFrames {
    var previous = next()
    do { val now = next(); val seconds = (now - previous) / 1_000_000_000f; previous = now } while (step(seconds))
}

internal suspend fun BrowsePinchProgressOwner.maintainAnchor(reduced: Boolean, contact: () -> Boolean,
    correct: suspend () -> Unit) = withFrames {
    val start = next()
    do { val now = next(); correct() } while (contact() || now - start < if (reduced) 32_000_000L else 450_000_000L)
}

/** The existing transaction playback also owns keyed drag motion; durations remain private to the semantic role. */
internal suspend fun BrowsePinchProgressOwner.dragLanding(from: Float, reduced: Boolean, actionSlot: Boolean,
    frame: (Float) -> Unit) = animate(from, 1f, sceneTween(catalog, if (actionSlot) io.github.anastaci1a.localica.ui.spec.MotionProfile.ActionLanding else io.github.anastaci1a.localica.ui.spec.MotionProfile.Placement, reduced, false), budgeted = false, frame = frame)

internal suspend fun BrowsePinchProgressOwner.dragHandoff(from: Float, reduced: Boolean,
    frame: (Float) -> Unit) = animate(from, 1f, sceneTween(catalog, io.github.anastaci1a.localica.ui.spec.MotionProfile.ActionHandoff, reduced, false), budgeted = false, frame = frame)

internal suspend fun BrowsePinchProgressOwner.edgeScroll(active: () -> Boolean, speed: () -> Float,
    edge: () -> Float, actionSlot: Boolean, scroll: suspend (Float) -> Float, afterScroll: () -> Unit,
    awaitMotion: suspend () -> Unit, awaitUnblocked: suspend () -> Unit) = withFrames {
    var previous = next()
    while (active()) {
        if (speed() == 0f) { awaitMotion(); previous = next() }
        val now = next()
        val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .032f)
        previous = now
        if (!active()) break
        val velocity = speed()
        if (velocity != 0f) {
            val consumed = scroll(velocity * edge() * (if (actionSlot) 8f else 12f) * seconds)
            afterScroll()
            if (!actionSlot && consumed == 0f) { awaitUnblocked(); previous = next() }
        }
    }
}

/** Hit geometry is data; the edge law stays with the shared root playback. */
internal fun dragEdgeVelocity(point: androidx.compose.ui.geometry.Offset, bounds: androidx.compose.ui.geometry.Rect,
    edge: Float, horizontal: Boolean, before: Boolean, after: Boolean): Float {
    if (!bounds.contains(point) || edge <= 0f) return 0f
    val at = if (horizontal) point.x else point.y
    val start = if (horizontal) bounds.left else bounds.top
    val end = if (horizontal) bounds.right else bounds.bottom
    return when {
        at < start + edge && before -> -((start + edge - at) / edge).coerceIn(0f, 1f)
        at > end - edge && after -> ((at - end + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}
