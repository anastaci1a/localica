package io.github.anastaci1a.localica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Deterministic standard-discovery contracts; no activity, callbacks or timing benchmark. */
class NavigationFrameWindowTest {
    private val drained = NavigationFrameWindow.TailStatus.BOUNDED_DRAIN_FINISHED
    private val before = NavigationFrameWindow.Position.BEFORE
    private val inside = NavigationFrameWindow.Position.IN_WINDOW
    private val after = NavigationFrameWindow.Position.AFTER

    @Test fun halfOpenMembershipUsesFrameTimestampsNotArrivalOrDuration() {
        val collector = NavigationFrameWindow(4, false)
        collector.record(99, 150, 1)
        collector.record(100, 350, 1)
        collector.record(199, 500, Long.MAX_VALUE)
        collector.record(200, 600, 0)
        val snapshot = collector.finish(100, 200, drained)
        assertEquals(listOf(before, inside, inside, after), (0 until 4).map { snapshot[it].position })
        assertEquals(1, snapshot.beforeWindowCount)
        assertEquals(2, snapshot.inWindowCount)
        assertEquals(1, snapshot.afterWindowCount)
        assertEquals(2, snapshot.lateInWindowCount)
        assertFalse(snapshot[0].arrivedAtOrAfterEnd)
        assertTrue(snapshot[1].lateInWindow)
        assertTrue(snapshot[3].arrivedAtOrAfterEnd)
        assertFalse(snapshot[3].lateInWindow)
        assertEquals(Long.MAX_VALUE, snapshot[2].totalDurationNanos)
    }

    @Test fun outOfOrderDuplicatesAndOutOfWindowLateRecordsKeepDeliveryOrder() {
        val collector = NavigationFrameWindow(5, false)
        val frames = longArrayOf(120, 80, 120, 250, 150)
        frames.forEachIndexed { index, timestamp -> collector.record(timestamp, 300L + index, index.toLong()) }
        val snapshot = collector.finish(100, 200, drained)
        assertEquals(5, snapshot.storedCount)
        for (index in frames.indices) {
            assertEquals(frames[index], snapshot[index].frameTimestampNanos)
            assertEquals(300L + index, snapshot[index].callbackArrivalNanos)
            assertEquals(index.toLong(), snapshot[index].totalDurationNanos)
        }
        assertEquals(listOf(inside, before, inside, after, inside), frames.indices.map { snapshot[it].position })
        assertEquals(3, snapshot.lateInWindowCount)
        assertTrue(snapshot[1].arrivedAtOrAfterEnd)
        assertTrue(snapshot[3].arrivedAtOrAfterEnd)
    }

    @Test fun arrivalExactlyAtEndIsLateButCannotExcludeAnInWindowFrame() {
        val collector = NavigationFrameWindow(2, false)
        collector.record(150, 199, 4)
        collector.record(151, 200, 4)
        val snapshot = collector.finish(100, 200, drained)
        assertEquals(2, snapshot.inWindowCount)
        assertFalse(snapshot[0].lateInWindow)
        assertTrue(snapshot[1].lateInWindow)
        assertEquals(1, snapshot.lateInWindowCount)
    }

    @Test fun overflowDoesNotOverwriteAcceptedRecordsOrLoseReportedDropAccounting() {
        val collector = NavigationFrameWindow(2, false)
        assertTrue(collector.record(99, 101, 3, listenerDroppedCount = 2))
        assertTrue(collector.record(100, 102, 4, listenerDroppedCount = 3))
        assertFalse(collector.record(199, 250, 5, listenerDroppedCount = 7))
        assertFalse(collector.record(201, 260, 6, listenerDroppedCount = 11))
        val snapshot = collector.finish(100, 200)
        assertEquals(4L, snapshot.receivedCount)
        assertEquals(2, snapshot.storedCount)
        assertEquals(2L, snapshot.bufferOverflowCount)
        assertEquals(23L, snapshot.reportedListenerDropCount)
        assertEquals(99L, snapshot[0].frameTimestampNanos)
        assertEquals(100L, snapshot[1].frameTimestampNanos)
        assertEquals(2, snapshot[0].listenerDroppedCount)
        assertEquals(3, snapshot[1].listenerDroppedCount)
        assertEquals(1, snapshot.inWindowCount) // Rejected records are not silently reconstructed.
        assertTrue(snapshot.incompleteTail)
        assertTrue(snapshot.hasQualityFailure)
    }

    @Test fun listenerDropsBufferOverflowAndIncompleteTailAreIndependent() {
        val listener = NavigationFrameWindow(1, false)
        listener.record(0, 0, 0, listenerDroppedCount = 5)
        val listenerOnly = listener.finish(0, 1, drained)
        assertEquals(5L, listenerOnly.reportedListenerDropCount)
        assertEquals(0L, listenerOnly.bufferOverflowCount)
        assertFalse(listenerOnly.incompleteTail)
        assertTrue(listenerOnly.hasQualityFailure)

        val buffer = NavigationFrameWindow(1, false)
        buffer.record(0, 0, 0)
        buffer.record(0, 0, 0)
        val bufferOnly = buffer.finish(0, 1, drained)
        assertEquals(0L, bufferOnly.reportedListenerDropCount)
        assertEquals(1L, bufferOnly.bufferOverflowCount)
        assertFalse(bufferOnly.incompleteTail)
        assertTrue(bufferOnly.hasQualityFailure)

        val tailOnly = NavigationFrameWindow(1, false).finish(0, 1)
        assertEquals(0L, tailOnly.reportedListenerDropCount)
        assertEquals(0L, tailOnly.bufferOverflowCount)
        assertTrue(tailOnly.incompleteTail)
        assertTrue(tailOnly.hasQualityFailure)
    }

    @Test fun capacitiesAreExplicitlyBoundedBeforeAllocatingBuffers() {
        for (capacity in listOf(Int.MIN_VALUE, -1, 0, NavigationFrameWindow.MAX_CAPACITY + 1, Int.MAX_VALUE)) {
            assertTrue(runCatching { NavigationFrameWindow(capacity, false) }.exceptionOrNull() is IllegalArgumentException)
        }
        val collector = NavigationFrameWindow(1, false)
        assertEquals(1, collector.capacity)
        assertTrue(collector.record(0, 0, 0))
        assertFalse(collector.record(0, 0, 0))
    }

    @Test fun invalidRangesDoNotFinalizeOrDiscardCollectedRecords() {
        val collector = NavigationFrameWindow(2, false)
        collector.record(10, 15, 5)
        for ((start, end) in listOf(10L to 10L, 11L to 10L, Long.MAX_VALUE to Long.MIN_VALUE)) {
            assertTrue(runCatching { collector.finish(start, end, drained) }.exceptionOrNull() is IllegalArgumentException)
        }
        collector.record(19, 25, 6)
        val snapshot = collector.finish(10, 20, drained)
        assertEquals(2L, snapshot.receivedCount)
        assertEquals(2, snapshot.inWindowCount)
    }

    @Test fun signedClockOriginsAndExtremeBoundsNeedNoTimestampArithmetic() {
        val collector = NavigationFrameWindow(4, false)
        collector.record(Long.MIN_VALUE, -1, 0)
        collector.record(-1, 0, 0)
        collector.record(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE)
        collector.record(Long.MAX_VALUE, Long.MAX_VALUE, 0)
        val snapshot = collector.finish(Long.MIN_VALUE, Long.MAX_VALUE, drained)
        assertEquals(3, snapshot.inWindowCount)
        assertEquals(1, snapshot.afterWindowCount)
        assertEquals(0, snapshot.beforeWindowCount)
        assertEquals(inside, snapshot[0].position)
        assertEquals(after, snapshot[3].position)
    }

    @Test fun unsupportedDeadlineMetricsRemainUnavailableWithoutRefreshFallback() {
        val collector = NavigationFrameWindow(2, false)
        assertTrue(runCatching { collector.record(10, 11, 100, deadlineDurationNanos = 16) }
            .exceptionOrNull() is IllegalArgumentException)
        collector.record(10, 11, 100)
        val snapshot = collector.finish(0, 20, drained)
        assertFalse(snapshot.deadlineMetricsSupported)
        assertEquals(1L, snapshot.receivedCount)
        assertNull(snapshot[0].deadlineDurationNanos)
        assertNull(snapshot[0].exceedsReportedDeadline)
    }

    @Test fun supportedDeadlinesKeepMissingZeroAndExactValuesDistinct() {
        val collector = NavigationFrameWindow(4, true)
        collector.record(0, 0, 9)
        collector.record(1, 1, 0, 0)
        collector.record(2, 2, 10, 10)
        collector.record(3, 3, 11, 10)
        val snapshot = collector.finish(0, 4, drained)
        assertTrue(snapshot.deadlineMetricsSupported)
        assertNull(snapshot[0].exceedsReportedDeadline)
        assertEquals(0L, snapshot[1].deadlineDurationNanos)
        assertEquals(false, snapshot[1].exceedsReportedDeadline)
        assertEquals(false, snapshot[2].exceedsReportedDeadline)
        assertEquals(true, snapshot[3].exceedsReportedDeadline)
    }

    @Test fun invalidMetricRecordsDoNotChangeCapacityOrAnyLossCounter() {
        val collector = NavigationFrameWindow(1, true)
        assertTrue(runCatching { collector.record(1, 2, -1, listenerDroppedCount = 3) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { collector.record(1, 2, 1, -2, 3) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { collector.record(1, 2, 1, listenerDroppedCount = -1) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(collector.record(1, 2, 1))
        assertTrue(runCatching { collector.record(1, 2, 1, -2, 9) }
            .exceptionOrNull() is IllegalArgumentException)
        val snapshot = collector.finish(0, 3, drained)
        assertEquals(1L, snapshot.receivedCount)
        assertEquals(0L, snapshot.reportedListenerDropCount)
        assertEquals(0L, snapshot.bufferOverflowCount)
        assertFalse(snapshot.hasQualityFailure)
    }

    @Test fun finishIsTerminalAndPostFinishRecordsFailInsteadOfVanishing() {
        val collector = NavigationFrameWindow(1, false)
        collector.record(10, 30, 5)
        val snapshot = collector.finish(0, 20, drained)
        assertTrue(runCatching { collector.record(15, 40, 8, listenerDroppedCount = 9) }
            .exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { collector.finish(0, 20, drained) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1L, snapshot.receivedCount)
        assertEquals(0L, snapshot.reportedListenerDropCount)
        assertEquals(0L, snapshot.bufferOverflowCount)
        assertEquals(30L, snapshot[0].callbackArrivalNanos)
        assertEquals(1, snapshot.lateInWindowCount)
    }

    @Test fun snapshotsDefensivelyOwnTheirBuffersAndReturnDetachedValues() {
        val frames = longArrayOf(10)
        val arrivals = longArrayOf(30)
        val durations = longArrayOf(5)
        val deadlines = longArrayOf(4)
        val drops = intArrayOf(2)
        val snapshot = NavigationFrameWindow.Snapshot(0, 20, true, drained, 1, 2, 0,
            frames, arrivals, durations, deadlines, drops)
        frames[0] = 999; arrivals[0] = 999; durations[0] = 999; deadlines[0] = 999; drops[0] = 999
        val record = snapshot[0]
        assertEquals(10L, record.frameTimestampNanos)
        assertEquals(30L, record.callbackArrivalNanos)
        assertEquals(5L, record.totalDurationNanos)
        assertEquals(4L, record.deadlineDurationNanos)
        assertEquals(2, record.listenerDroppedCount)
        assertEquals(inside, record.position)
        assertEquals(1, snapshot.inWindowCount)
        assertEquals(1, snapshot.lateInWindowCount)
        assertEquals(10L, snapshot[0].frameTimestampNanos)
        assertNotEquals(record.copy(frameTimestampNanos = 999), snapshot[0])
        assertTrue(runCatching { snapshot[-1] }.exceptionOrNull() is IndexOutOfBoundsException)
        assertTrue(runCatching { snapshot[1] }.exceptionOrNull() is IndexOutOfBoundsException)
    }

    @Test fun offOwnerMutationAndFinalizationFailButDetachedSnapshotsCanBeRead() {
        val collector = NavigationFrameWindow(1, false)
        val recordFailure = AtomicReference<Throwable?>()
        val finishFailure = AtomicReference<Throwable?>()
        val other = Thread {
            recordFailure.set(runCatching { collector.record(0, 0, 0) }.exceptionOrNull())
            finishFailure.set(runCatching { collector.finish(0, 1, drained) }.exceptionOrNull())
        }
        other.start()
        other.join() // Synchronization only, not a sleep, timeout or timing assertion.
        assertTrue(recordFailure.get() is IllegalStateException)
        assertTrue(finishFailure.get() is IllegalStateException)
        collector.record(0, 0, 0)
        val snapshot = collector.finish(0, 1, drained)
        val observed = AtomicReference<NavigationFrameWindow.Observation>()
        val reader = Thread { observed.set(snapshot[0]) }
        reader.start()
        reader.join()
        assertEquals(snapshot[0], observed.get())
        assertEquals(1L, snapshot.receivedCount)
    }

    @Test fun emptyCaptureRetainsExplicitTailStatusWithoutInventingObservations() {
        val snapshot = NavigationFrameWindow(1, false).finish(0, 1)
        assertEquals(0L, snapshot.receivedCount)
        assertEquals(0, snapshot.storedCount)
        assertEquals(0, snapshot.beforeWindowCount)
        assertEquals(0, snapshot.inWindowCount)
        assertEquals(0, snapshot.afterWindowCount)
        assertEquals(0, snapshot.lateInWindowCount)
        assertTrue(snapshot.incompleteTail)
        assertTrue(snapshot.hasQualityFailure)
    }
}
