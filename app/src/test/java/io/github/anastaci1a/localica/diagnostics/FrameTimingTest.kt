package io.github.anastaci1a.localica.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameTimingTest {
    @Test fun refreshBudgetAndDeadlineAreIndependentAndIdleGapsAreNotMisses() {
        val timing = FrameTiming()
        timing.add(1, 8_000_000, 8_333_333, 7_000_000, 0)
        timing.add(1_000_000_000, 17_000_000, 8_333_333, 20_000_000, 0)
        val s = timing.snapshot()
        assertEquals(50.0, s.jankPercent!!, 0.0)
        assertEquals(2L, s.estimatedMissedVsyncs)
        assertEquals(1, s.deadlineMisses)
        assertEquals(8.0, s.p50Ms!!, 0.0)
        assertEquals(17.0, s.p90Ms!!, 0.0)
        assertEquals(17.0, s.p99Ms!!, 0.0)
    }
    @Test fun emptyAndUnavailableAreNotReportedAsZeroJank() {
        val timing = FrameTiming()
        assertNull(timing.snapshot().jankPercent)
        timing.add(5, 10, 10, -1, 0)
        val s = timing.snapshot()
        assertEquals(0, s.deadlineSamples)
        assertEquals(0L, s.estimatedMissedVsyncs)
        assertEquals(0.0, s.jankPercent!!, 0.0)
    }
    @Test fun boundsOverflowAndListenerLossRemainExplicit() {
        val timing = FrameTiming(2)
        timing.add(10, 100, 10, -1, 2)
        timing.add(20, 200, 10, -1, 3)
        timing.add(30, 300, 10, -1, 4)
        timing.add(40, -1, 10, -1, 1)
        val s = timing.snapshot(20, 30)
        assertEquals(1, s.count)
        assertEquals(10L, s.listenerDrops)
        assertEquals(1L, s.overflow)
        assertEquals(1L, s.invalid)
        assertEquals(19L, s.estimatedMissedVsyncs)
    }
}
