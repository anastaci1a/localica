package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.ui.MotionDurationScale
import io.github.anastaci1a.localica.ui.components.landDrag
import io.github.anastaci1a.localica.ui.components.handoffDrag
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RootFrameOperationsTest {
    private val dispatcher = Dispatchers.Unconfined
    private fun scope() = CoroutineScope(dispatcher + SupervisorJob())

    @Test fun warmLayoutUsesTwoRootFramesAndRetiresWithoutAnExtraFrame() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
            val scope = scope()
            val job = scope.launch { owner.warmLayout() }
            assertEquals(1, h.clock.activeCount)
            h.advance(.01); assertFalse(job.isCompleted)
            h.advance(.01); assertTrue(job.isCompleted)
            assertEquals(0, h.clock.activeCount)
            scope.cancel()
        }
    }

    @Test fun suspendedMutationDoesNotKeepClockLiveOrReplayOldFrames() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<Long>()
            val scope = scope()
            val job = scope.launch { owner.withFrames { seen += next(); gate.await(); seen += next() } }
            h.advance(.01)
            assertEquals(0, h.clock.activeCount)
            // Another root participant can present frames while the Foundation mutation suspends.
            val other = scope.launch { owner.catchUp(100) { _, _ -> } }
            repeat(4) { h.advance(.02) }
            gate.complete(Unit)
            assertEquals(1, seen.size)
            val resumedAfter = h.now
            h.advance(.01)
            assertTrue(job.isCompleted)
            assertTrue(seen.last() > resumedAfter)
            other.cancel(); scope.cancel()
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun cancellationAndThrowRetireRequestedFrameAndPreserveFailure() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
            val scope = scope()
            val cancel = scope.launch { owner.withFrames { next() } }
            cancel.cancel(); assertEquals(0, h.clock.activeCount)
            val marker = IllegalArgumentException("operation failure")
            var failure: Throwable? = null
            val job = scope.launch {
                try { owner.withFrames { next(); throw marker } } catch (t: Throwable) { failure = t }
            }
            h.advance(.01)
            assertTrue(job.isCompleted); assertSame(marker, failure)
            assertEquals(0, h.clock.activeCount); scope.cancel()
        }
    }

    @Test fun dragRecipeMatchesDeliveredTweenAtEveryPresentedTimeIncludingSkippedFrames() {
        for (action in listOf(false, true)) for (reduced in listOf(false, true)) for (scale in listOf(0f, 1f, 4f / 3f, 2f)) {
            EngineHarness().use { h ->
                val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
                val scope = scope()
                val values = mutableListOf<Float>()
                val duration = if (reduced) 0 else if (action) 180 else 350
                val expected = TargetBasedAnimation(tween<Float>(duration), Float.VectorConverter, 0f, 1f)
                val durationScale = object : MotionDurationScale { override val scaleFactor = scale }
                val job = scope.launch(durationScale) { owner.dragLanding(0f, reduced, action) { values += it } }
                h.advance(.01)
                val first = h.now
                assertEquals(expected.getValueFromNanos(if (scale == 0f) expected.durationNanos else 0L).toRawBits(), values.last().toRawBits())
                for (dt in listOf(.008, .016, .070, .120, .200, .300)) {
                    if (job.isCompleted) break
                    h.advance(dt)
                    assertEquals(expected.getValueFromNanos(((h.now - first) / scale).toLong()).toRawBits(), values.last().toRawBits())
                }
                assertTrue(job.isCompleted); assertEquals(0, h.clock.activeCount)
                scope.cancel()
            }
        }
    }

    @Test fun anchorOwnsBothContactAndTheExactReleaseAllowance() {
        for (reduced in listOf(false, true)) EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
            val scope = scope(); var contact = true; var corrections = 0
            val job = scope.launch { owner.maintainAnchor(reduced, { contact }) { corrections++ } }
            h.advance(.01)
            repeat(50) { h.advance(.01) }
            assertFalse(job.isCompleted)
            contact = false; h.advance(.01)
            assertTrue(job.isCompleted); assertEquals(51, corrections)
            assertEquals(0, h.clock.activeCount); scope.cancel()
        }
    }
    @Test fun alreadyAvailableRootFrameCannotArriveBeforeItsWaiterIsInstalled() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, dispatcher) }
            h.advance(.01) // A source is allowed to have the next frame already available.
            val immediate = object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = false
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
            }
            val scope = CoroutineScope(immediate + SupervisorJob())
            var seen: Long? = null
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { owner.withFrames { seen = next() } }
            try {
                assertTrue("An immediately available root frame must complete the pending request", job.isCompleted)
                assertEquals(h.now, seen)
                assertEquals(0, h.clock.activeCount)
            } finally { scope.cancel() }
        }
    }

    @Test fun dragProgressKeepsTheExistingRunningObservationThroughCompletionAndCancellation() {
        EngineHarness().use { h ->
            val family = io.github.anastaci1a.localica.ui.components.BrowsePinchProgressOwner()
            family.motion.bind(h.clock, dispatcher)
            val progress = family.create(0f)
            val scope = scope()
            assertFalse(progress.isRunning)
            val landing = scope.launch {
                family.landDrag(progress, false, io.github.anastaci1a.localica.ui.components.KeyedDragContent.ListItems)
            }
            assertTrue(progress.isRunning)
            h.advance(.01); h.advance(.5)
            assertTrue(landing.isCompleted); assertFalse(progress.isRunning)
            val handoff = scope.launch { family.handoffDrag(progress, false) }
            assertTrue(progress.isRunning)
            handoff.cancel(); assertFalse(progress.isRunning)
            assertEquals(0, h.clock.activeCount); scope.cancel()
        }
    }

}
