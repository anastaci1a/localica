package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshots.Snapshot
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsScrollMotionTest {
    private suspend fun frame(h: EngineHarness, seconds: Double) {
        h.advance(seconds)
        Snapshot.sendApplyNotifications()
        yield()
    }
    private fun observe(clock: UiClock, created: MutableList<Pair<Long, Float>>, joins: MutableList<MotionJoin>) {
        clock.events = object : MotionEvents {
            override fun created(motion: MotionFloat) { created += clock.stampNow.nanos to motion.valueAt(clock.stampNow) }
            override fun joined(motion: MotionFloat, join: MotionJoin) { joins += join }
            override fun retired(motion: MotionFloat, stamp: FrameStamp) {}
        }
    }

    @Test fun nativeMutationAdmissionPrecedesTheInitialSampleAndClockOrigin() = runBlocking {
        withTimeout(5000) { EngineHarness().use { h ->
            val scroll = ScrollState(0)
            val release = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val created = mutableListOf<Pair<Long, Float>>()
            observe(h.clock, created, mutableListOf())
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                scroll.scroll {
                    scrollBy(40f)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await(); scrollBy(30f) }
                    }
                }
            }
            val next = launch(start = CoroutineStart.UNDISPATCHED) { SettingsScrollMotion(h.clock, scroll).scrollTo(120) }
            cleaning.await()
            assertTrue("No trajectory may exist while native mutation admission waits", created.isEmpty())
            assertEquals(0, h.clock.activeCount)
            h.now += 1_000_000_000L
            release.complete(Unit)
            old.join()
            yield()
            assertEquals(1, created.size)
            assertEquals("Sample the preceding mutator's final position", 70f, created.single().second, 0f)
            assertEquals("Start at admission, not before the queue wait", h.now, created.single().first)
            assertEquals(70, scroll.value)
            frame(h, .262)
            next.join()
            assertEquals(120, scroll.value)
            assertEquals(0, h.clock.activeCount)
        } }
    }

    @Test fun cancellationBeforeAdmissionNeverCreatesAMotion() = runBlocking {
        withTimeout(5000) { EngineHarness().use { h ->
            val scroll = ScrollState(0)
            val release = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val created = mutableListOf<Pair<Long, Float>>()
            observe(h.clock, created, mutableListOf())
            val old = launch(start = CoroutineStart.UNDISPATCHED) { scroll.scroll {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            } }
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { SettingsScrollMotion(h.clock, scroll).scrollTo(100) }
            cleaning.await()
            waiting.cancelAndJoin()
            assertTrue(created.isEmpty())
            assertEquals(0, h.clock.activeCount)
            release.complete(Unit)
            old.join()
            assertEquals(0, scroll.value)
        } }
    }

    @Test fun cancellingAnAdmittedScrollRetiresItsClockAndKeepsTheShownPosition() = runBlocking {
        withTimeout(5000) { EngineHarness().use { h ->
            val scroll = ScrollState(0)
            val moving = launch(start = CoroutineStart.UNDISPATCHED) { SettingsScrollMotion(h.clock, scroll).scrollTo(120) }
            frame(h, .1)
            val shown = scroll.value
            assertTrue("The admitted trajectory must advance", shown in 1..119)
            moving.cancelAndJoin()
            assertEquals(1, h.clock.activeCount)
            frame(h, .161)
            assertEquals("Retain the cancelled solver until its original deadline", 1, h.clock.activeCount)
            assertEquals(shown, scroll.value)
            frame(h, .001)
            assertEquals(0, h.clock.activeCount)
            assertEquals(shown, scroll.value)
        } }
    }

    @Test fun catalogPlansPreserveTheOld262And175MillisecondDurationsAndReducedSnap() = runBlocking {
        withTimeout(5000) {
            for ((access, milliseconds) in listOf(MotionAccess.Standard to 262, MotionAccess.Faster to 175, MotionAccess.Reduced to 0)) {
                EngineHarness(access).use { h ->
                    val scroll = ScrollState(0)
                    val joins = mutableListOf<MotionJoin>()
                    observe(h.clock, mutableListOf(), joins)
                    val moving = launch(start = CoroutineStart.UNDISPATCHED) { SettingsScrollMotion(h.clock, scroll).scrollTo(120) }
                    if (milliseconds == 0) {
                        assertTrue(moving.isCompleted)
                        assertTrue(joins.any { it.policy == "Reduced" })
                    } else {
                        assertEquals(milliseconds / 1000.0, joins.single { it.afterKind == 1 }.finishSeconds!!, 0.0)
                        frame(h, (milliseconds - 1) / 1000.0)
                        assertFalse("The plan must last the complete original duration: $access", moving.isCompleted)
                        frame(h, .001)
                        moving.join()
                    }
                    assertEquals(120, scroll.value)
                    assertEquals(0, h.clock.activeCount)
                }
            }
        }
    }
}
