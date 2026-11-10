package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** The oracle is the immutable bc90075ba pinchFrames algorithm, before shared-clock adoption. */
class BrowsePinchProgressOwnerTest {
    private suspend fun legacyFrames(durationNanos: Long, scale: Float, frame: (Long) -> Unit) {
        val owner = currentCoroutineContext().job
        if (scale <= 0f || durationNanos <= 0L) { withFrameNanos { if (owner.isActive) frame(durationNanos) }; return }
        val laterBudget = maxOf(1_000_000_000L / 60, durationNanos / 5)
        var budget = 1_000_000_000L / 60
        var previous = withFrameNanos { if (owner.isActive) frame(0L); it }
        var played = 0L
        while (played < durationNanos) {
            previous = withFrameNanos { now ->
                played = minOf(durationNanos, played + ((now - previous).coerceIn(0L, budget) / scale).toLong())
                if (owner.isActive) frame(played)
                now
            }
            budget = laterBudget
        }
    }

    private class Pump : AutoCloseable {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = UiClock(object : FrameSource {
            override suspend fun awaitFrame() = frames.receive()
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }, { Long.MIN_VALUE / 4 }, {})
        val owner = BrowsePinchProgressOwner().also { it.bind(clock, Dispatchers.Unconfined) }
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { clock.run() } }
        fun send(nanos: Long) { check(frames.trySend(nanos).isSuccess) }
        override fun close() { scope.cancel(); frames.close() }
    }

    private data class Trace(val values: List<Int>, val callbacks: List<String>)

    private fun trace(shared: Boolean, intervals: List<Long>, scale: Float, duration: Int,
        catchUp: Boolean, terminalMutation: Boolean = false, terminalCancellation: Boolean = false): Trace {
        val pump = if (shared) Pump() else null
        val frames = BroadcastFrameClock()
        val scope = pump?.scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + frames)
        val access = object : MotionDurationScale { override val scaleFactor = scale }
        var value = .125f
        var target = .61f
        val values = mutableListOf<Int>()
        val callbacks = mutableListOf<String>()
        lateinit var caller: Job
        try {
            caller = scope.launch(access, start = CoroutineStart.UNDISPATCHED) {
                val from = value
                fun apply(fraction: Float, terminal: Boolean) {
                    if (terminal && catchUp) {
                        callbacks += "caught:${value.toRawBits()}:${target.toRawBits()}"
                        if (terminalMutation) target = .83f
                        if (terminalCancellation) caller.cancel()
                    }
                    value = if (catchUp) from + (target - from) * fraction else fraction
                    callbacks += "write:${value.toRawBits()}"
                }
                if (shared) {
                    if (catchUp) pump!!.owner.catchUp(duration, ::apply)
                    else pump!!.owner.animate(from, 1f, tween(duration)) { apply(it, false) }
                } else {
                    val animation = if (catchUp) TargetBasedAnimation(tween<Float>(duration, easing = FastOutSlowInEasing),
                        Float.VectorConverter, 0f, 1f) else TargetBasedAnimation(tween<Float>(duration),
                        Float.VectorConverter, from, 1f)
                    legacyFrames(animation.durationNanos, if (catchUp) 1f else scale) { played ->
                        apply(animation.getValueFromNanos(played), played >= animation.durationNanos)
                    }
                }
            }
            var nanos = 1_000_000_000L
            var index = 0
            while (caller.isActive && index < 1000) {
                if (index > 0) nanos += intervals[(index - 1).coerceAtMost(intervals.lastIndex)]
                if (catchUp && index == 2) target = .29f
                if (shared) pump!!.send(nanos) else frames.sendFrame(nanos)
                values += value.toRawBits()
                index++
            }
            assertFalse("The intent must terminate", caller.isActive)
            assertEquals(0, pump?.clock?.activeCount ?: 0)
            return Trace(values, callbacks)
        } finally {
            if (pump != null) pump.close() else scope.cancel()
        }
    }

    @Test fun everyDisplayedFloatAndTerminalCallbackMatchesThePinnedAlgorithm() {
        val regular = listOf(8_333_333L, 16_666_667L)
        val sequences = regular.map { listOf(it) } + listOf(40L, 80L, 150L, 400L).flatMap { millis ->
            listOf(listOf(millis * 1_000_000L, 8_333_333L), listOf(millis * 1_000_000L))
        }
        var samples = 0
        for (intervals in sequences) for (scale in listOf(0f, .5f, 1f, 2f)) {
            for (duration in listOf(0, 100, 220)) for (catchUp in listOf(false, true)) {
                val before = trace(false, intervals, scale, duration, catchUp)
                val after = trace(true, intervals, scale, duration, catchUp)
                assertEquals("$intervals scale=$scale duration=$duration catchUp=$catchUp", before, after)
                samples += before.values.size
            }
        }
        println("Pinned bc90075ba equality: ${sequences.size * 4 * 3 * 2} cases, $samples displayed samples, zero differing Float bits or callback events")
        assertTrue("The corpus must contain realistic intermediate frames", samples > 1000)
    }

    @Test fun terminalCallbackChangesTargetAndCancelsBeforeTheSameFrameWrite() {
        for (cancel in listOf(false, true)) {
            val before = trace(false, listOf(16_666_667L), 2f, 100, true, true, cancel)
            val after = trace(true, listOf(16_666_667L), 2f, 100, true, true, cancel)
            assertEquals(before, after)
            assertEquals(.83f.toRawBits(), after.values.last())
            assertTrue(after.callbacks[after.callbacks.lastIndex - 1].startsWith("caught:"))
            assertTrue(after.callbacks.last().startsWith("write:"))
        }
    }

    @Test fun cancellationBeforeFrameEntryRetiresWithoutAnotherWrite() {
        Pump().use { pump ->
            val seen = mutableListOf<Float>()
            val caller = pump.scope.launch { pump.owner.catchUp(100) { value, _ -> seen += value } }
            pump.send(1_000_000_000L)
            assertEquals(listOf(0f), seen)
            caller.cancel()
            assertEquals(0, pump.clock.activeCount)
            pump.send(1_016_666_667L)
            assertEquals(listOf(0f), seen)
        }
    }

    @Test fun enteringRootReducedRetiresOrdinaryMotionAndPreservesPinchPlatformTiming() {
        Pump().use { pump ->
            val initial = EngineContext(1f / 120, 1000f, MotionAccess.Standard)
            pump.clock.configure(initial, StockUiSpec)
            var ordinaryRetired = false
            val ordinary = object : ActiveMotion {
                override val id = pump.clock.allocateId()
                override val presented = true
                override val settled get() = ordinaryRetired
                override fun endsAt(stamp: FrameStamp) = false
                override fun finish(stamp: FrameStamp) {}
                override fun reduce(stamp: FrameStamp) { ordinaryRetired = true; pump.clock.unregister(this) }
            }
            pump.clock.register(ordinary)
            val seen = mutableListOf<Float>()
            val caller = pump.scope.launch { pump.owner.catchUp(100) { value, _ -> seen += value } }
            pump.send(1_000_000_000L)
            pump.clock.configure(initial.copy(access = MotionAccess.Reduced), StockUiSpec)
            assertTrue(ordinaryRetired)
            assertEquals(1, pump.clock.activeCount)
            assertTrue(caller.isActive)
            assertEquals(listOf(0f), seen)
            repeat(9) { pump.send(1_016_666_667L + it * 16_666_667L) }
            assertFalse(caller.isActive)
            assertEquals(1f, seen.last(), 0f)
            assertEquals(0, pump.clock.activeCount)
        }
    }

    @Test fun callbackFailureRetiresItsIntentAndKeepsTheRootAvailable() {
        Pump().use { pump ->
            val failure = IllegalStateException("intent witness")
            var observed: Throwable? = null
            val caller = pump.scope.launch {
                try { pump.owner.catchUp(100) { _, _ -> throw failure } }
                catch (error: Throwable) { observed = error }
            }
            pump.send(1_000_000_000L)
            assertFalse(caller.isActive)
            assertTrue(observed is IllegalStateException)
            assertEquals(failure.message, observed?.message)
            assertSame(failure, observed?.cause ?: observed)
            assertEquals(0, pump.clock.activeCount)
            var value = 0f
            val next = pump.scope.launch { pump.owner.animate(0f, 1f, tween(0)) { value = it } }
            pump.send(1_016_666_667L)
            assertFalse(next.isActive)
            assertEquals(1f, value, 0f)
        }
    }
}
