package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/** Foundation keeps its mutation mutex; this lease only supplies the existing root's time. */
private class ScrollRoot(val clock: UiClock, var users: Int = 1)
private val scrollClocks = java.util.IdentityHashMap<MonotonicFrameClock, ScrollRoot>()
internal fun registerScrollClock(key: MonotonicFrameClock, clock: UiClock): () -> Unit {
    clock.checkMutation()
    val entry = scrollClocks[key]
    check(entry == null || entry.clock === clock) { "A frame clock already has a different scroll root" }
    if (entry == null) scrollClocks[key] = ScrollRoot(clock) else entry.users++
    return { clock.checkMutation(); if (--scrollClocks.getValue(key).users == 0) scrollClocks.remove(key); Unit }
}
@Composable internal fun bindScrollClock(clock: UiClock) {
    val key = requireNotNull(rememberCoroutineScope().coroutineContext[MonotonicFrameClock])
    DisposableEffect(key, clock) { val release = registerScrollClock(key, clock); onDispose(release) }
}
internal suspend fun <T> withScrollExecution(block: suspend ScrollExecution.() -> T): T {
    val clock = requireNotNull(scrollClocks[coroutineContext[MonotonicFrameClock]]?.clock) { "Scroll requires its registered root UiClock" }
    val execution = ScrollExecution(clock)
    try { return execution.block() } finally { execution.close() }
}

internal class ScrollExecution(private val clock: UiClock) : ActiveMotion, AutoCloseable {
    private val frames = Channel<FrameStamp>(Channel.CONFLATED)
    private val observer = FrameObserver { stamp, _ -> frames.trySend(stamp); Unit }
    private var reduced = clock.context?.access == MotionAccess.Reduced
    override val id = clock.allocateId()
    override val presented = true
    override var settled = false; private set
    val spec = clock.spec.motion.getValue(MotionProfile.Scroll) as ListMotionSpec
    private val fadeCurve = Bezier(spec.fadeEasing)
    private val sigma = if (clock.context?.access == MotionAccess.Faster) 2f / 3 else 1f
    init { clock.observers.add(observer); clock.register(this) }
    override fun endsAt(stamp: FrameStamp) = false
    override fun finish(stamp: FrameStamp) = Unit
    override fun reduce(stamp: FrameStamp) { reduced = true; clock.unregister(this); frames.trySend(stamp) }
    override fun close() { clock.checkMutation(); settled = true; clock.unregister(this); clock.observers.remove(observer); frames.close() }

    private suspend fun nextFrame(): FrameStamp = frames.receive().also { yield() }

    fun fadeProgress(elapsedMs: Float) = fadeCurve.at((elapsedMs / spec.fadeMs).coerceIn(0f, 1f).toDouble()).toFloat()

    /** First accepted stamp is the origin, matching Foundation's former initial frame wait. */
    suspend fun animate(duration: Int, fade: Boolean = false, step: suspend (progress: Float, elapsedMs: Float, frame: Int, finished: Boolean) -> Boolean) {
        val easing = Bezier(if (fade) spec.fadeEasing else spec.easing)
        val span = duration.coerceIn(1, 500) * sigma
        if (reduced) { step(1f, duration.toFloat(), 0, true); return }
        val start = nextFrame().nanos
        var frame = 0
        while (true) {
            val stamp = if (reduced) clock.stampNow else nextFrame()
            val elapsed = (stamp.nanos - start) / 1_000_000f
            val time = if (reduced) 1f else (elapsed / span).coerceIn(0f, 1f)
            if (!step(easing.at(time.toDouble()).toFloat(), if (reduced) duration.toFloat() else elapsed / sigma, ++frame, time >= 1f) || time >= 1f) return
        }
    }
}
