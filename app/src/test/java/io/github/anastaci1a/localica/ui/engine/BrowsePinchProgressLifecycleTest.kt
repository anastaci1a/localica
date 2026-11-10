package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.components.BrowsePinchProgress
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Cross-owner and component lifecycle controls for the reviewed root-clock seam. */
class BrowsePinchProgressLifecycleTest {
    private class Pump : AutoCloseable {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failure = CompletableDeferred<Throwable>()
        val clock = UiClock(object : FrameSource {
            override suspend fun awaitFrame() = frames.receive()
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }, { Long.MIN_VALUE / 4 }, {})
        val owner = BrowsePinchProgressOwner().also { it.bind(clock, Dispatchers.Unconfined) }
        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { clock.run() }
                catch (error: Throwable) { if (error !is CancellationException) failure.complete(error) }
            }
        }
        fun send(nanos: Long) { check(frames.trySend(nanos).isSuccess) }
        override fun close() { scope.cancel(); frames.close() }
    }

    @Test fun geometryOnlyHandleIsInertAndUnboundAnimationFailsExplicitly() = runBlocking {
        val progress = BrowsePinchProgress(.125f)
        progress.follow(.7f)
        progress.snapTo(.3f)
        assertEquals(.3f, progress.value, 0f)
        var failure: Throwable? = null
        try { progress.animateTo(1f) } catch (error: Throwable) { failure = error }
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("shared root UiClock"))
        assertEquals(.3f, progress.value, 0f)
    }

    @Test fun bindingIsIdempotentForOneRootAndRejectsAnotherRoot() {
        Pump().use { first -> Pump().use { second ->
            first.owner.bind(first.clock, Dispatchers.Unconfined)
            first.owner.bind(first.clock, Dispatchers.Unconfined)
            assertEquals(0, first.clock.activeCount)
            var rejected = false
            try { first.owner.bind(second.clock, Dispatchers.Unconfined) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals(0, second.clock.activeCount)
            rejected = false
            try { first.owner.bind(first.clock, Dispatchers.Default) } catch (_: IllegalStateException) { rejected = true }
            assertTrue("A bound root cannot replace its retirement dispatcher", rejected)
        } }
    }

    @Test fun offOwnerCompletionCancellationAndDisposalRetireWithoutExtraWrites() {
        for (mode in listOf("completed", "cancelled", "disposed")) {
            var onOwner = true
            var callerDispatches = 0
            var ownerDispatches = 0
            fun dispatcher(owner: Boolean) = object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: CoroutineContext) = true
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (owner) ownerDispatches++ else callerDispatches++
                    val previous = onOwner
                    onOwner = owner
                    try { block.run() } finally { onOwner = previous }
                }
            }
            val ownerDispatcher = dispatcher(true)
            val callerDispatcher = dispatcher(false)
            val errors = mutableListOf<Throwable>()
            val handler = CoroutineExceptionHandler { _, failure -> errors += failure }
            val rootScope = CoroutineScope(SupervisorJob() + ownerDispatcher + handler)
            val callerScope = CoroutineScope(SupervisorJob() + callerDispatcher + handler)
            val frames = Channel<Long>(Channel.UNLIMITED)
            val clock = UiClock(object : FrameSource {
                override suspend fun awaitFrame() = frames.receive()
                override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = onFrame(frames.receive())
            }, { Long.MIN_VALUE / 4 }, { check(onOwner) { "Engine mutation requires Main" } })
            val owner = BrowsePinchProgressOwner().also { it.bind(clock, ownerDispatcher) }
            rootScope.launch(start = CoroutineStart.UNDISPATCHED) { clock.run() }
            var writes = 0
            try {
                // Start on the owner, then resume this same caller on its own dispatcher.
                val caller = callerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.catchUp(100) { _, _ -> writes++ }
                }
                onOwner = false
                check(frames.trySend(1_000_000_000L).isSuccess)
                assertEquals(1, writes)
                when (mode) {
                    "completed" -> {
                        repeat(9) { check(frames.trySend(1_016_666_667L + it * 16_666_667L).isSuccess) }
                        assertTrue(caller.isCompleted)
                        assertFalse(caller.isCancelled)
                        caller.cancel() // Already-completed work remains retired.
                    }
                    "cancelled" -> caller.cancel()
                    "disposed" -> { rootScope.cancel(); callerScope.cancel() }
                }
                assertTrue("$mode must finish without leaving a suspended caller", caller.isCompleted)
                assertEquals("$mode must leave no registered motion", 0, clock.activeCount)
                assertTrue("The caller must actually resume away from the owner", callerDispatches > 0)
                assertTrue("Cleanup must dispatch back to the owner", ownerDispatches > 1)
                assertTrue("$mode must preserve Main enforcement: $errors", errors.isEmpty())
                val terminalWrites = writes
                check(frames.trySend(2_000_000_000L).isSuccess)
                assertEquals("$mode cannot publish another value", terminalWrites, writes)
                assertEquals(0, clock.activeCount)
            } finally {
                callerScope.cancel()
                rootScope.cancel()
                frames.close()
            }
        }
    }

    @Test fun idleResumeEmitsTheRetainedValueOnItsFirstPresentedFrame() {
        Pump().use { pump ->
            val progress = BrowsePinchProgress(.125f, pump.owner)
            assertEquals(0, pump.clock.activeCount)
            val first = pump.scope.launch { progress.animateTo(1f) }
            pump.send(1_000_000_000L)
            assertEquals(.125f, progress.value, 0f)
            pump.send(1_016_666_667L)
            val retained = progress.value
            assertTrue(retained > .125f)
            first.cancel()
            assertEquals(0, pump.clock.activeCount)
            // A source callback already awaited before cancellation may drain while idle.
            pump.send(2_000_000_000L)
            assertEquals(retained, progress.value, 0f)
            val resumed = pump.scope.launch { progress.animateTo(0f) }
            pump.send(3_000_000_000L)
            assertEquals(retained, progress.value, 0f)
            pump.send(3_150_000_000L)
            assertTrue(progress.value < retained)
            assertTrue(progress.value > retained * .8f)
            resumed.cancel()
            assertEquals(0, pump.clock.activeCount)
        }
    }

    @Test fun cancellationDuringRetirementPreservesAnAlreadyObservedCallbackFailure() {
        var onOwner = true
        var deferCleanup = false
        val queued = ArrayDeque<Runnable>()
        fun dispatcher(owner: Boolean, defer: Boolean = false) = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = true
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val guarded = Runnable {
                    val previous = onOwner
                    onOwner = owner
                    try { block.run() } finally { onOwner = previous }
                }
                if (defer && deferCleanup) queued.addLast(guarded) else guarded.run()
            }
        }
        val rootScope = CoroutineScope(SupervisorJob() + dispatcher(true))
        val callerScope = CoroutineScope(SupervisorJob() + dispatcher(false))
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = UiClock(object : FrameSource {
            override suspend fun awaitFrame() = frames.receive()
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }, { Long.MIN_VALUE / 4 }, { check(onOwner) { "Engine mutation requires Main" } })
        val owner = BrowsePinchProgressOwner().also { it.bind(clock, dispatcher(true, true)) }
        rootScope.launch(start = CoroutineStart.UNDISPATCHED) { clock.run() }
        val witness = IllegalStateException("callback failure precedes cancellation")
        var observed: Throwable? = null
        try {
            val caller = callerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { owner.catchUp(100) { _, _ -> throw witness } }
                catch (failure: Throwable) { observed = failure }
            }
            deferCleanup = true
            onOwner = false
            check(frames.trySend(1_000_000_000L).isSuccess)
            assertEquals(1, queued.size)
            caller.cancel()
            while (queued.isNotEmpty()) queued.removeFirst().run()
            assertTrue(caller.isCompleted)
            assertTrue(observed is IllegalStateException)
            assertEquals(witness.message, observed?.message)
            assertEquals(0, clock.activeCount)
        } finally {
            callerScope.cancel(); rootScope.cancel(); frames.close()
        }
    }

    @Test fun componentTerminalCallbackMutatesTargetAndCancelsBeforeTheValueWrite() {
        Pump().use { pump ->
            val progress = BrowsePinchProgress(.125f, pump.owner)
            progress.follow(.61f)
            val events = mutableListOf<String>()
            var beforeWrite = Float.NaN
            lateinit var caller: Job
            caller = pump.scope.launch {
                progress.catchUp {
                    beforeWrite = progress.value
                    events += "callback:${beforeWrite.toRawBits()}"
                    progress.follow(.83f)
                    caller.cancel()
                }
            }
            var frame = 0
            while (caller.isActive && frame < 20) {
                pump.send(1_000_000_000L + frame * 16_666_667L)
                events += "presented:${progress.value.toRawBits()}"
                frame++
            }
            assertFalse(caller.isActive)
            assertTrue(beforeWrite < .61f)
            assertEquals(.83f.toRawBits(), progress.value.toRawBits())
            assertTrue(events[events.lastIndex - 1].startsWith("callback:"))
            assertEquals("presented:${.83f.toRawBits()}", events.last())
            assertEquals(0, pump.clock.activeCount)
        }
    }

    @Test fun callbackCancelsASiblingAndDefersReplacementUntilTheNextFrame() {
        Pump().use { pump ->
            var changed = false
            var siblingWrites = 0
            var replacementWrites = 0
            lateinit var sibling: Job
            val first = pump.scope.launch {
                pump.owner.catchUp(100) { _, _ ->
                    if (!changed) {
                        changed = true
                        sibling.cancel()
                        pump.scope.launch { pump.owner.animate(0f, 1f, tween(0)) { replacementWrites++ } }
                    }
                }
            }
            sibling = pump.scope.launch { pump.owner.catchUp(100) { _, _ -> siblingWrites++ } }
            pump.send(1_000_000_000L)
            assertEquals(0, siblingWrites)
            assertEquals(0, replacementWrites)
            assertEquals(2, pump.clock.activeCount)
            pump.send(1_016_666_667L)
            assertEquals(1, replacementWrites)
            assertEquals(1, pump.clock.activeCount)
            first.cancel()
            assertEquals(0, pump.clock.activeCount)
        }
    }

    @Test fun reductionDrainsNewOrdinaryWorkWhileLeavingTheCompatibilityOwnerActive() {
        Pump().use { pump ->
            val context = EngineContext(1f / 120, 1000f, MotionAccess.Standard)
            pump.clock.configure(context, StockUiSpec)
            val events = mutableListOf<String>()
            fun ordinary(label: String, after: () -> Unit = {}): ActiveMotion = object : ActiveMotion {
                override val id = pump.clock.allocateId()
                override val presented = true
                override val settled = false
                override fun endsAt(stamp: FrameStamp) = false
                override fun finish(stamp: FrameStamp) {}
                override fun reduce(stamp: FrameStamp) {
                    events += label
                    pump.clock.unregister(this)
                    after()
                }
            }
            val second = ordinary("second")
            val first = ordinary("first") { pump.clock.register(second) }
            val pinch = pump.scope.launch { pump.owner.catchUp(100) { _, _ -> } }
            pump.clock.register(first)
            pump.clock.configure(context.copy(access = MotionAccess.Reduced), StockUiSpec)
            assertEquals(listOf("first", "second"), events)
            assertEquals(1, pump.clock.activeCount)
            assertTrue(pinch.isActive)
            pinch.cancel()
            assertEquals(0, pump.clock.activeCount)
        }
    }

    @Test fun disposingTheOwningScopeRetiresAllSuspendedIntents() {
        val pump = Pump()
        var writes = 0
        val caller = pump.scope.launch { pump.owner.catchUp(100) { _, _ -> writes++ } }
        pump.send(1_000_000_000L)
        assertEquals(1, writes)
        pump.close()
        assertTrue(caller.isCancelled)
        assertEquals(0, pump.clock.activeCount)
        assertEquals(1, writes)
    }

    @Test fun rootAccessTransitionKeepsTheLegacyPlatformScaledReleaseTrace() {
        fun trace(changeAccess: Boolean): List<Int> = Pump().use { pump ->
            val context = EngineContext(1f / 120, 1000f, MotionAccess.Standard)
            pump.clock.configure(context, StockUiSpec)
            val progress = BrowsePinchProgress(.125f, pump.owner)
            val scale = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = 2f }
            val caller = pump.scope.launch(scale) { progress.animateTo(1f) }
            val values = mutableListOf<Int>()
            var frame = 0
            while (caller.isActive && frame < 100) {
                if (changeAccess && frame == 2)
                    pump.clock.configure(context.copy(access = MotionAccess.Reduced), StockUiSpec)
                pump.send(1_000_000_000L + frame * 8_333_333L)
                values += progress.value.toRawBits()
                frame++
            }
            assertFalse(caller.isActive)
            assertTrue("A doubled platform scale retains the approximately 440 ms release", frame >= 53)
            assertEquals(1f.toRawBits(), values.last())
            assertEquals(0, pump.clock.activeCount)
            values
        }
        assertEquals(trace(false), trace(true))
    }

    @Test fun repeatedAndBackwardRootStampsRemainRejected() = runBlocking {
        for (invalid in listOf(1_000_000_000L, 999_999_999L)) Pump().use { pump ->
            var writes = 0
            pump.scope.launch { pump.owner.catchUp(100) { _, _ -> writes++ } }
            pump.send(1_000_000_000L)
            pump.send(invalid)
            assertTrue(pump.failure.isCompleted)
            assertTrue(pump.failure.await() is IllegalStateException)
            assertEquals(1, writes)
            assertEquals(0, pump.clock.activeCount)
        }
    }
}
