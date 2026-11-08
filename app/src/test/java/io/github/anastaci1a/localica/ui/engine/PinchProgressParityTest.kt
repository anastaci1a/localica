package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** The pre-adoption BrowsePinchProgress (c5cac7d3), kept verbatim as the timing reference. */
private class LegacyPinchProgress(initial: Float) {
    var value = initial; private set
    private val mutations = MutatorMutex()
    private var fingerTarget = initial
    fun follow(target: Float) { fingerTarget = target }
    suspend fun snapTo(target: Float) = mutations.mutate { value = target }
    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) = mutations.mutate {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, value, target)
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        pinchFrames(animation.durationNanos, scale) { played -> value = animation.getValueFromNanos(played) }
    }
    suspend fun catchUp(onCaughtUp: () -> Unit) = mutations.mutate {
        val from = value
        val ease = TargetBasedAnimation(tween<Float>(100, easing = FastOutSlowInEasing), Float.VectorConverter, 0f, 1f)
        pinchFrames(ease.durationNanos, 1f) { played ->
            val fraction = ease.getValueFromNanos(played)
            if (played >= ease.durationNanos) onCaughtUp()
            value = from + (fingerTarget - from) * fraction
        }
    }
    private suspend fun pinchFrames(durationNanos: Long, scale: Float, frame: (Long) -> Unit) {
        val owner = currentCoroutineContext().job
        if (scale <= 0f || durationNanos <= 0L) { withFrameNanos { if (owner.isActive) frame(durationNanos) }; return }
        val laterBudget = maxOf(PinchFrameBudgetNanos, durationNanos / PinchEaseMinimumFrames)
        var budget = PinchFrameBudgetNanos
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
}

class PinchProgressParityTest {
    private class Run(val legacy: MutableList<Float> = mutableListOf(), val engine: MutableList<Float> = mutableListOf(), val times: MutableList<Double> = mutableListOf())

    /** Runs the same intent on both implementations over [intervalsMillis]; records both values after every frame. */
    private fun lockstep(from: Float, scale: Float = 1f, intervalsMillis: List<Double>, maxFrames: Int = 80,
        intent: suspend (legacy: LegacyPinchProgress, engine: PinchProgressMotion) -> Unit, frameHook: (Int) -> Unit = {}): Run =
        EngineHarness().use { h ->
            val frames = LockstepFrames(h)
            val evidence = BoundedEvidence(400)
            val run = Run()
            EngineObservation(h.clock, evidence).use { observer ->
                val legacy = LegacyPinchProgress(from)
                val engine = PinchProgressMotion(from)
                val context = if (scale == 1f) frames else frames + object : MotionDurationScale { override val scaleFactor = scale }
                runBlocking(context) {
                    val job = launch(start = CoroutineStart.UNDISPATCHED) { intent(legacy, engine) }
                    yield()
                    assertEquals("Before the first frame both show the start value", legacy.value, engine.value(), 0f)
                    var index = 0; var elapsed = 0.0
                    while (job.isActive && index < maxFrames) {
                        val dt = intervalsMillis.getOrElse(index) { intervalsMillis.last() }
                        if (index > 0) elapsed += dt; index++
                        frames.tick(dt)
                        frameHook(index)
                        run.legacy += legacy.value; run.engine += engine.value(); run.times += elapsed
                        assertEquals("Frame $index at ${elapsed}ms", legacy.value, engine.value(), 0f)
                    }
                    assertFalse("The intent completes within $maxFrames frames", job.isActive)
                    assertEquals(legacy.value, engine.value(), 0f)
                    // The superseded or finished channels must all have reached Rest and retired.
                    engine.retire()
                    repeat(8) { frames.tick(100.0) }
                    assertEquals(0, h.clock.activeCount)
                }
                assertEquals(0, observer.continuity.registeredCount)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
            frames.release()
            run
        }

    private fun Run.largestStep(from: Float, to: Float) = (listOf(from) + engine).zipWithNext { a, b -> (b - a) / (to - from) }.max()

    @Test fun catchUpMatchesTheLegacyEaseOnRegularFramesAndStillSpansEightyToOneHundredTwentyMilliseconds() {
        for (interval in listOf(1000.0 / 60, 1000.0 / 120)) {
            var caught = 0
            val run = lockstep(.2f, intervalsMillis = listOf(interval), intent = { l, e -> l.follow(.7f); e.follow(.7f)
                coroutineScope { launch { l.catchUp { caught++ } }; launch { e.catchUp { caught++ } } } })
            assertEquals(2, caught)
            assertEquals(.2f, run.engine.first(), 0f)
            val end = run.times[run.engine.indexOfFirst { it >= .7f }]
            assertTrue("Catch-up takes 80-120 ms, not $end at $interval ms frames", end in 80.0..120.0)
            assertTrue(run.largestStep(.2f, .7f) < .5f)
        }
    }

    @Test fun aSlowDestinationFrameDoesNotCollapseTheCatchUp() {
        for (stall in listOf(40.0, 80.0, 150.0, 400.0)) {
            val run = lockstep(.1f, intervalsMillis = listOf(stall) + List(40) { 1000.0 / 120 }, intent = { l, e -> l.follow(.4f); e.follow(.4f)
                coroutineScope { launch { l.catchUp {} }; launch { e.catchUp {} } } })
            assertTrue("A $stall ms frame jumps ${run.engine[1]} in one frame", (run.engine[1] - .1f) / .3f < .2f)
            assertTrue(run.engine.indexOfFirst { it >= .4f } >= 4)
            assertTrue(run.largestStep(.1f, .4f) < .34f)
        }
    }

    @Test fun releaseSettleSurvivesASlowFirstFrameAndKeepsTheSystemAnimationScale() {
        val regular = lockstep(.3f, intervalsMillis = listOf(1000.0 / 60), intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
        assertEquals(220.0, regular.times.last(), 17.0)
        val stalled = lockstep(.3f, intervalsMillis = listOf(150.0) + List(40) { 1000.0 / 60 }, intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
        assertTrue(stalled.engine[1] > .3f * .8f)
        assertTrue(stalled.largestStep(.3f, 0f) < .25f)
        val fast = lockstep(.3f, scale = .5f, intervalsMillis = listOf(1000.0 / 60), intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
        assertEquals("Half animation scale halves the settle", 110.0, fast.times.last(), 17.0)
        val off = lockstep(.3f, scale = 0f, intervalsMillis = listOf(1000.0 / 60), intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
        assertEquals("Animations off settle on the first frame", 1, off.engine.size)
        assertEquals(0f, off.engine.single(), 0f)
    }

    @Test fun sustainedSlowFramesKeepTheSettleWithinAFewDisplayedFrames() {
        for (interval in listOf(100.0, 200.0, 400.0)) {
            val run = lockstep(.3f, intervalsMillis = listOf(interval), intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
            assertTrue(run.engine.size <= PinchEaseMinimumFrames + 2 && run.engine.size >= PinchEaseMinimumFrames + 1)
            assertTrue(run.times.last() <= (PinchEaseMinimumFrames + 1) * interval + 1)
            assertTrue(run.engine[1] > .3f * .8f)
            assertTrue(run.largestStep(.3f, 0f) < .5f)
        }
    }

    @Test fun twoConsecutiveSlowFramesCloseLessThanHalfTheGap() {
        for (stall in listOf(120.0, 150.0, 400.0)) {
            val catchUp = lockstep(0f, intervalsMillis = listOf(stall, stall) + List(40) { 1000.0 / 120 }, intent = { l, e -> l.follow(.3f); e.follow(.3f)
                coroutineScope { launch { l.catchUp {} }; launch { e.catchUp {} } } })
            assertTrue(catchUp.largestStep(0f, .3f) < .5f)
            val settle = lockstep(.3f, intervalsMillis = listOf(stall, stall) + List(40) { 1000.0 / 60 }, intent = { l, e -> coroutineScope { launch { l.animateTo(0f, tween(220)) }; launch { e.animateTo(0f, tween(220)) } } })
            assertTrue(settle.largestStep(.3f, 0f) < .5f)
        }
    }

    /** The live finger target moves every frame, and the completion callback itself moves it once more. */
    @Test fun movingFingersAndACallbackRetargetAreReadAfterTheCallbackOnEveryFrame() {
        var legacy: LegacyPinchProgress? = null; var engine: PinchProgressMotion? = null
        val run = lockstep(.5f, intervalsMillis = listOf(1000.0 / 60), intent = { l, e -> legacy = l; engine = e; l.follow(.6f); e.follow(.6f)
            coroutineScope { launch { l.catchUp { l.follow(.95f) } }; launch { e.catchUp { e.follow(.95f) } } } },
            frameHook = { index -> val target = .6f + index * .02f; legacy!!.follow(target); engine!!.follow(target) })
        assertEquals(.95f, run.engine.last(), 0f)
    }

    /** Cancelling the tracking job freezes the shown value where the last frame left it, exactly as the MutatorMutex did. */
    @Test fun cancellationFreezesTheValueAndTheSupersededChannelRetiresThroughItsOwnRest() = EngineHarness().use { h ->
        val frames = LockstepFrames(h)
        val evidence = BoundedEvidence(200)
        EngineObservation(h.clock, evidence).use { observer ->
            val legacy = LegacyPinchProgress(.2f); val engine = PinchProgressMotion(.2f)
            runBlocking(frames) {
                legacy.follow(.8f); engine.follow(.8f)
                val l = launch(start = CoroutineStart.UNDISPATCHED) { legacy.catchUp {} }; val e = launch(start = CoroutineStart.UNDISPATCHED) { engine.catchUp {} }; yield()
                repeat(3) { frames.tick(1000.0 / 60) }
                assertTrue(engine.value() > .2f && engine.value() < .8f)
                l.cancel(); e.cancel(); yield(); yield()
                val frozen = engine.value()
                assertEquals(legacy.value, frozen, 0f)
                repeat(12) { frames.tick(1000.0 / 60); assertEquals("Frozen after cancellation", frozen, engine.value(), 0f); assertEquals(legacy.value, engine.value(), 0f) }
                assertEquals("The superseded channel finished and retired; the frozen Rest channel is idle", 0, h.clock.activeCount)
                // A fresh intent after the freeze starts from the frozen value.
                val next = launch { engine.snapTo(.8f) }; yield(); next.join()
                assertEquals(.8f, engine.value(), 0f)
                engine.retire(); repeat(2) { frames.tick(100.0) }
            }
            assertEquals(0, observer.continuity.registeredCount)
            assertEquals(emptyList<Violation>(), evidence.violations)
        }
        frames.release()
    }

    @Test fun completionCallbackRetiringTheTransactionPublishesNoStaleKnotAndKeepsTheTerminalValue() = EngineHarness().use { h ->
        val frames = LockstepFrames(h)
        val evidence = BoundedEvidence(200)
        EngineObservation(h.clock, evidence).use { observer ->
            val engine = PinchProgressMotion(0f)
            runBlocking(frames) {
                engine.follow(.3f)
                val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.catchUp { engine.retire() } }; yield()
                repeat(12) { frames.tick(1000.0 / 60) }
                assertFalse(job.isActive)
                assertEquals(.3f, engine.value(), 0f)
                repeat(3) { frames.tick(100.0) }
                assertEquals(0, h.clock.activeCount)
            }
            assertEquals(0, observer.continuity.registeredCount)
            assertEquals(emptyList<Violation>(), evidence.violations)
        }
        frames.release()
    }

    @Test fun reducedAccessMidFlightLeavesPinchChannelsSampledAndHistoricalReadsStayExactAcrossCompletion() = EngineHarness().use { h ->
        val frames = LockstepFrames(h)
        val evidence = BoundedEvidence(200)
        EngineObservation(h.clock, evidence).use { observer ->
            val legacy = LegacyPinchProgress(.1f); val engine = PinchProgressMotion(.1f)
            runBlocking(frames) {
                legacy.follow(.9f); engine.follow(.9f)
                launch(start = CoroutineStart.UNDISPATCHED) { legacy.catchUp {} }; launch(start = CoroutineStart.UNDISPATCHED) { engine.catchUp {} }; yield()
                repeat(2) { frames.tick(1000.0 / 60) }
                val channel = requireNotNull(engine.current)
                val midStamp = h.clock.stamp; val midValue = channel.value()
                h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
                assertEquals("Reduced does not reduce a legacy pinch channel", 1, h.clock.activeCount)
                repeat(10) { frames.tick(1000.0 / 60); assertEquals(legacy.value, engine.value(), 0f) }
                assertTrue(channel.settled)
                assertEquals("Completed playback history is retained", midValue, channel.valueAt(midStamp), 0f)
                assertEquals(.9f, channel.valueAt(h.clock.stamp), 0f)
                h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), StockUiSpec)
                engine.retire(); repeat(2) { frames.tick(100.0) }
            }
            assertEquals(0, observer.continuity.registeredCount)
            assertEquals(emptyList<Violation>(), evidence.violations)
        }
        frames.release()
    }

    @Test fun aPinchProgressStaysBoundToOneRootClock() = EngineHarness().use { first -> EngineHarness().use { second ->
        val a = LockstepFrames(first); val b = LockstepFrames(second)
        val engine = PinchProgressMotion(0f)
        runBlocking(a) { engine.snapTo(.5f) }
        val failure = runCatching { runBlocking(b) { engine.snapTo(.6f) } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(.5f, engine.value(), 0f)
        engine.retire(); a.release(); b.release()
    } }
}
