package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class RootFrameAdmissionTest {
    private fun EngineHarness.access(access: MotionAccess) =
        clock.configure(EngineContext(1f / 120, 1000f, access), StockUiSpec)

    @Test fun registrationAndRemovalWhileAwaitIsSuspendedCannotBypassPreparation() {
        EngineHarness().use { h ->
            val calls = mutableListOf<String>()
            var scale = 1
            fun probe(id: Long) = object : ActiveMotion {
                private var captured = 0
                override val id = id; override val presented = true; override val settled = false
                override fun prepareFrame() { captured = scale; calls += "prepare-$id-$captured" }
                override fun sampleFrame(stamp: FrameStamp) { calls += "sample-$id-$captured" }
                override fun endsAt(stamp: FrameStamp) = true
                override fun finish(stamp: FrameStamp) { calls += "finish-$id" }
                override fun reduce(stamp: FrameStamp) = Unit
            }
            val removed = probe(1)
            h.clock.register(removed)
            assertEquals(listOf("prepare-1-1"), calls)
            scale = 2
            h.clock.unregister(removed)
            val late = probe(2)
            h.clock.register(late)
            h.advance(.01)
            assertEquals(listOf("prepare-1-1", "prepare-2-2"), calls)
            h.advance(.01)
            assertEquals(listOf("prepare-1-1", "prepare-2-2", "sample-2-2", "finish-2"), calls)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun snapshotReductionSkipsRemovedMembersAndDoesNotDrainCallbackCreatedWork() {
        EngineHarness().use { h ->
            val reductions = mutableListOf<Int>()
            lateinit var removed: ActiveMotion
            fun probe(id: Int, follows: Boolean = true, reduce: () -> Unit = {}) = object : ActiveMotion {
                override val id = id.toLong()
                override val followsRoot = follows
                override val admitsReduced = !follows
                override val presented = true
                override val settled = false
                override fun endsAt(stamp: FrameStamp) = false
                override fun finish(stamp: FrameStamp) = Unit
                override fun reduce(stamp: FrameStamp) { reductions += id; h.clock.unregister(this); reduce() }
            }
            val surviving = probe(3, false)
            val created = probe(4, false)
            val first = probe(1) { h.clock.unregister(removed); h.clock.register(created) }
            removed = probe(2)
            listOf(first, removed, surviving).forEach { h.clock.register(it) }
            h.access(MotionAccess.Reduced)
            assertEquals(listOf(1), reductions)
            assertEquals(2, h.clock.activeCount)
            h.access(MotionAccess.Standard); h.access(MotionAccess.Reduced)
            assertEquals(listOf(1), reductions)
            h.clock.unregister(surviving); h.clock.unregister(created)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun frameSamplesPrecedeCompletionAndObservationAndSkipCallbackRemovals() {
        EngineHarness().use { h ->
            val calls = mutableListOf<String>()
            lateinit var removed: ActiveMotion
            val next = object : ActiveMotion {
                override val id = 3L; override val presented = true; override val settled = false
                override fun sampleFrame(stamp: FrameStamp) { calls += "next-sample" }
                override fun endsAt(stamp: FrameStamp) = true
                override fun finish(stamp: FrameStamp) { calls += "next-finish" }
                override fun reduce(stamp: FrameStamp) = Unit
            }
            fun probe(id: Long) = object : ActiveMotion {
                override val id = id; override val presented = true; override val settled = false
                override fun sampleFrame(stamp: FrameStamp) {
                    calls += "sample-$id"
                    if (id == 1L) { h.clock.unregister(removed); h.clock.register(next) }
                }
                override fun endsAt(stamp: FrameStamp): Boolean { calls += "end-$id"; return true }
                override fun finish(stamp: FrameStamp) { calls += "finish-$id" }
                override fun reduce(stamp: FrameStamp) = Unit
            }
            val first = probe(1); removed = probe(2)
            // Install while the clock is suspended; admission order is deterministic.
            h.clock.register(first); h.clock.register(removed)
            val observer = FrameObserver { _, _ -> calls += "observe" }
            h.clock.observers += observer
            h.advance(.01)
            assertEquals(listOf("sample-1", "end-1", "finish-1", "observe"), calls)
            calls.clear(); h.advance(.01)
            assertEquals(listOf("next-sample", "next-finish", "observe"), calls)
            assertEquals(0, h.clock.activeCount)
            h.clock.observers.remove(observer)
        }
    }
}
