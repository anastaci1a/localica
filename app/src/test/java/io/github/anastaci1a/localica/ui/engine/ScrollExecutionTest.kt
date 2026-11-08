package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.MonotonicFrameClock
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlin.coroutines.coroutineContext

private suspend fun <T> withScrollClock(clock: UiClock, block: suspend () -> T): T {
    val release = registerScrollClock(requireNotNull(coroutineContext[MonotonicFrameClock]), clock)
    try { return block() } finally { release() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollExecutionTest {
    @Before fun installMain() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }
    private val frameClock = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = error("A scroll must not request its own frame")
    }
    private suspend fun drain() { repeat(5) { yield() } }
    @Test fun fixtureOwnerUsesOnlyItsVirtualStampsAndCleansUpAtRest() = runBlocking(frameClock) {
        EngineHarness().use { fixture ->
            withScrollClock(fixture.clock) {
                val elapsed = mutableListOf<Float>()
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    withScrollExecution { animate(100) { _, ms, _, _ -> elapsed += ms; true } }
                }
                assertEquals(1, fixture.clock.activeCount)
                assertEquals(1, fixture.clock.observers.size)
                fixture.advance(.016); drain()
                fixture.advance(.025); drain()
                assertEquals(listOf(25f), elapsed)
                fixture.advance(.075); drain()
                job.join()
                assertEquals(100f, elapsed.last(), 0f)
                assertEquals(0, fixture.clock.activeCount)
                assertTrue(fixture.clock.observers.isEmpty())
                val awaits = fixture.awaits
                fixture.advance(.1); drain()
                assertEquals("No idle clock await", awaits, fixture.awaits)
            }
            try { withScrollExecution {}; fail("Disposed registration must be absent") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sameFrameClockCannotSilentlySelectAnotherOwnerAndFailurePreservesTheFirst() = runBlocking(frameClock) {
        EngineHarness().use { fixture -> EngineHarness().use { outer ->
            withScrollClock(fixture.clock) {
                try { withScrollClock(outer.clock) { fail("Ambiguous roots") }; fail("Missing ambiguity guard") }
                catch (_: IllegalStateException) { }
                withScrollExecution {
                    assertEquals(1, fixture.clock.activeCount)
                    assertEquals(0, outer.clock.activeCount)
                }
            }
            withScrollClock(outer.clock) { withScrollExecution { assertEquals(1, outer.clock.activeCount) } }
            assertEquals(0, outer.clock.activeCount)
        } }
    }
    @Test fun inheritedRegistrationsShareOneRootUntilTheLastScopeDisposes() = runBlocking(frameClock) {
        EngineHarness().use { fixture ->
            withScrollClock(fixture.clock) {
                withScrollClock(fixture.clock) { withScrollExecution { assertEquals(1, fixture.clock.activeCount) } }
                withScrollExecution { assertEquals(1, fixture.clock.activeCount) }
            }
            try { withScrollExecution {}; fail("Last disposal must remove root registration") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun cancellingSuspendedFoundationWorkRemovesObserverAndLease() = runBlocking(frameClock) {
        EngineHarness().use { fixture -> withScrollClock(fixture.clock) {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { withScrollExecution { animate(500) { _, _, _, _ -> true } } }
            fixture.advance(.016); drain()
            job.cancelAndJoin()
            assertEquals(0, fixture.clock.activeCount)
            assertTrue(fixture.clock.observers.isEmpty())
        } }
    }
    @Test fun cancellationFromAWorkerConfinesFinalCleanupToTheMainOwner() {
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try { runBlocking(frameClock) {
                val clock = withContext(main) {
                    val owner = Thread.currentThread()
                    UiClock(object : FrameSource { override suspend fun awaitFrame(): Long = error("No frame required") },
                        { 1L }, { check(Thread.currentThread() === owner) { "Wrong owner thread" } })
                }
                val release = withContext(main) { registerScrollClock(frameClock, clock) }
                val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
                val scope = CoroutineScope(Dispatchers.Unconfined + frameClock + SupervisorJob() + CoroutineExceptionHandler { _, e -> errors += e })
                try {
                    val job = withContext(main) { scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        withScrollExecution { awaitCancellation() }
                    } }
                    job.cancelAndJoin()
                    withContext(main) { assertEquals(0, clock.activeCount); assertTrue(clock.observers.isEmpty()) }
                    assertTrue("Cleanup must not throw on the cancelling worker: $errors", errors.isEmpty())
                } finally { scope.cancel(); withContext(main) { release() } }
            } } finally { Dispatchers.setMain(Dispatchers.Unconfined) }
        }
    }
    @Test fun liveReductionFinishesWithoutAnotherNativeFrame() = runBlocking(frameClock) {
        EngineHarness().use { fixture -> withScrollClock(fixture.clock) {
            var progress = 0f
            val job = launch(start = CoroutineStart.UNDISPATCHED) { withScrollExecution { animate(500) { p, _, _, _ -> progress = p; true } } }
            fixture.advance(.016); drain()
            fixture.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            drain(); job.join()
            assertEquals(1f, progress, 0f)
            assertEquals(0, fixture.clock.activeCount)
            assertTrue(fixture.clock.observers.isEmpty())
        } }
    }
}
