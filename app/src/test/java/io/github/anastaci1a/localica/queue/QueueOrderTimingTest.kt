package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.queue.QueueOrderTiming.Outcome
import io.github.anastaci1a.localica.queue.QueueOrderTiming.Stage
import org.junit.Assert.*
import org.junit.Test

class QueueOrderTimingTest {
    private class Clock(var now: Long = 100L) {
        var reads = 0
        var failure: Exception? = null
        fun read(): Long {
            reads++
            failure?.let { throw it }
            return now
        }
    }

    @Test fun successfulOperationReportsMonotonicElapsedAndExplicitStageDurations() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        Stage.entries.drop(1).forEachIndexed { index, stage ->
            clock.now += (index + 1) * 10L
            assertTrue(timing.mark(stage))
        }
        clock.now += 8L
        val report = timing.finish(Outcome.Success)!!
        assertEquals(Outcome.Success, report.outcome)
        assertTrue(report.clockValid)
        assertEquals(288L, report.totalNanos)
        assertEquals(listOf(0L, 10L, 30L, 60L, 100L, 150L, 210L, 280L),
            Stage.entries.map(report::elapsedNanos))
        Stage.entries.zipWithNext().forEachIndexed { index, (from, through) ->
            assertEquals((index + 1) * 10L, report.durationNanos(from, through))
        }
        assertEquals(280L, report.durationNanos(Stage.Accepted, Stage.CallbackCompleted))
        assertNull(report.durationNanos(Stage.CallbackCompleted, Stage.Accepted))
    }

    @Test fun equalClockReadingsAreValidMeasuredZeroRatherThanAbsent() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        assertTrue(timing.mark(Stage.ActorStarted))
        val report = timing.finish(Outcome.Success)!!
        assertTrue(report.clockValid)
        assertEquals(0L, report.elapsedNanos(Stage.ActorStarted))
        assertEquals(0L, report.durationNanos(Stage.Accepted, Stage.ActorStarted))
        assertEquals(0L, report.totalNanos)
        assertNull(report.elapsedNanos(Stage.CursorCaptured))
    }

    @Test fun failedSaveRetainsPartialMilestonesWithoutClaimingDurabilityOrLaterWork() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 110L
        timing.mark(Stage.ActorStarted)
        clock.now = 130L
        timing.mark(Stage.CursorCaptured)
        timing.recordCounts(queueEntries = 50)
        clock.now = 190L
        val report = timing.finish(Outcome.Failure)!!
        assertEquals(Outcome.Failure, report.outcome)
        assertEquals(90L, report.totalNanos)
        assertEquals(30L, report.elapsedNanos(Stage.CursorCaptured))
        Stage.entries.drop(Stage.DurableEditSaved.ordinal).forEach {
            assertNull(report.elapsedNanos(it))
            assertNull(report.durationNanos(Stage.CursorCaptured, it))
        }
        assertEquals(QueueOrderTiming.Counts(queueEntries = 50), report.counts)
    }

    @Test fun cancellationBeforeActorStartRecordsOnlyAcceptanceAndTerminalTime() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 150L
        val report = timing.finish(Outcome.Cancelled)!!
        assertEquals(Outcome.Cancelled, report.outcome)
        assertEquals(50L, report.totalNanos)
        assertEquals(0L, report.elapsedNanos(Stage.Accepted))
        Stage.entries.drop(1).forEach { assertNull(report.elapsedNanos(it)) }
        assertEquals(QueueOrderTiming.Counts(), report.counts)
    }

    @Test fun inactiveOperationLeavesOptionalActivationPhasesAbsent() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        timing.mark(Stage.ActorStarted)
        clock.now = 110L
        timing.mark(Stage.CursorCaptured)
        clock.now = 160L
        timing.mark(Stage.DurableEditSaved)
        clock.now = 170L
        timing.mark(Stage.CallbackCompleted)
        val report = timing.finish(Outcome.Success)!!
        assertNull(report.elapsedNanos(Stage.FreshSnapshot))
        assertNull(report.elapsedNanos(Stage.GrantsTemplates))
        assertNull(report.elapsedNanos(Stage.MainNativeApiReturned))
        assertNull(report.durationNanos(Stage.DurableEditSaved, Stage.FreshSnapshot))
        assertEquals(10L, report.durationNanos(Stage.DurableEditSaved, Stage.CallbackCompleted))
    }

    @Test fun nativeApiReturnAndTerminalOutcomeDoNotFabricateCallbackCompletion() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 120L
        timing.mark(Stage.MainNativeApiReturned)
        clock.now = 150L
        val report = timing.finish(Outcome.Failure)!!
        assertEquals(20L, report.elapsedNanos(Stage.MainNativeApiReturned))
        assertNull(report.elapsedNanos(Stage.CallbackCompleted))
        assertNull(report.durationNanos(Stage.MainNativeApiReturned, Stage.CallbackCompleted))
    }

    @Test fun firstFinishWinsAndLaterCallsCannotMutateReportOrReadClock() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        timing.mark(Stage.ActorStarted)
        timing.recordCounts(queueEntries = 12, templatesBuilt = 4)
        val report = timing.finish(Outcome.Success)!!
        val original = report.copy()
        val reads = clock.reads
        clock.failure = IllegalStateException("Must not be called after completion")
        assertNull(timing.finish(Outcome.Failure))
        assertNull(timing.finish(Outcome.Cancelled))
        assertFalse(timing.mark(Stage.CallbackCompleted))
        assertFalse(timing.recordCounts(queueEntries = 90, templatesBuilt = 90))
        assertEquals(reads, clock.reads)
        assertEquals(original, report)
        assertEquals(QueueOrderTiming.Counts(queueEntries = 12, templatesBuilt = 4), report.counts)
        assertNull(report.elapsedNanos(Stage.CallbackCompleted))
    }

    @Test fun repeatedAndOutOfOrderMarksAreIgnoredWithoutSamplingClock() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        assertFalse(timing.mark(Stage.Accepted))
        clock.now = 120L
        assertTrue(timing.mark(Stage.CursorCaptured))
        val reads = clock.reads
        clock.now = 200L
        assertFalse(timing.mark(Stage.CursorCaptured))
        assertFalse(timing.mark(Stage.ActorStarted))
        assertEquals(reads, clock.reads)
        assertTrue(timing.mark(Stage.DurableEditSaved))
        val report = timing.finish(Outcome.Success)!!
        assertEquals(20L, report.elapsedNanos(Stage.CursorCaptured))
        assertNull(report.elapsedNanos(Stage.ActorStarted))
        assertEquals(100L, report.elapsedNanos(Stage.DurableEditSaved))
    }

    @Test fun aggregateCountsMergeAsTotalsAndDistinguishUnknownFromZero() {
        val timing = QueueOrderTiming { 0L }
        assertTrue(timing.recordCounts(queueEntries = 0, libraryTracks = Int.MAX_VALUE))
        assertTrue(timing.recordCounts(templatesReused = 10, templatesBuilt = 3))
        assertTrue(timing.recordCounts(templatesReused = 12))
        assertTrue(timing.recordCounts())
        val report = timing.finish(Outcome.Success)!!
        assertEquals(QueueOrderTiming.Counts(0, Int.MAX_VALUE, 12, 3), report.counts)
        assertEquals(QueueOrderTiming.Counts(), QueueOrderTiming { 0L }.finish(Outcome.Failure)!!.counts)
    }

    @Test fun eachNegativeCountRejectsTheWholeUpdateAndPreservesPriorObservations() {
        val timing = QueueOrderTiming { 0L }
        timing.recordCounts(queueEntries = 5, libraryTracks = 20, templatesReused = 3, templatesBuilt = 2)
        assertFalse(timing.recordCounts(queueEntries = -1, libraryTracks = 99))
        assertFalse(timing.recordCounts(queueEntries = 99, libraryTracks = -1))
        assertFalse(timing.recordCounts(queueEntries = 99, templatesReused = -1))
        assertFalse(timing.recordCounts(queueEntries = 99, templatesBuilt = -1))
        assertEquals(QueueOrderTiming.Counts(5, 20, 3, 2), timing.finish(Outcome.Failure)!!.counts)
    }

    @Test fun interleavedOwnersKeepTheirOwnClockMilestonesCountsAndCompletion() {
        val firstClock = Clock(100L)
        val secondClock = Clock(500L)
        val first = QueueOrderTiming(firstClock::read)
        val second = QueueOrderTiming(secondClock::read)
        firstClock.now = 110L
        secondClock.now = 540L
        first.mark(Stage.ActorStarted)
        second.mark(Stage.CursorCaptured)
        first.recordCounts(queueEntries = 1)
        second.recordCounts(queueEntries = 2, templatesBuilt = 2)
        val firstReport = first.finish(Outcome.Failure)!!
        secondClock.now = 580L
        second.mark(Stage.CallbackCompleted)
        val secondReport = second.finish(Outcome.Success)!!
        assertEquals(10L, firstReport.totalNanos)
        assertEquals(80L, secondReport.totalNanos)
        assertNull(firstReport.elapsedNanos(Stage.CursorCaptured))
        assertNull(secondReport.elapsedNanos(Stage.ActorStarted))
        assertEquals(QueueOrderTiming.Counts(queueEntries = 1), firstReport.counts)
        assertEquals(QueueOrderTiming.Counts(queueEntries = 2, templatesBuilt = 2), secondReport.counts)
        assertEquals(Outcome.Failure, firstReport.outcome)
        assertEquals(Outcome.Success, secondReport.outcome)
        assertNull(first.finish(Outcome.Success))
        assertNull(second.finish(Outcome.Failure))
    }

    @Test fun negativeAbsoluteOriginIsValid() {
        val clock = Clock(-200L)
        val timing = QueueOrderTiming(clock::read)
        clock.now = -170L
        timing.mark(Stage.ActorStarted)
        clock.now = 10L
        val report = timing.finish(Outcome.Success)!!
        assertTrue(report.clockValid)
        assertEquals(30L, report.elapsedNanos(Stage.ActorStarted))
        assertEquals(210L, report.totalNanos)
    }

    @Test fun signedNanoTimeWraparoundRetainsElapsedDuration() {
        val clock = Clock(Long.MAX_VALUE - 5)
        val timing = QueueOrderTiming(clock::read)
        clock.now = Long.MIN_VALUE + 4
        assertTrue(timing.mark(Stage.ActorStarted))
        clock.now = Long.MIN_VALUE + 14
        val report = timing.finish(Outcome.Success)!!
        assertTrue(report.clockValid)
        assertEquals(10L, report.elapsedNanos(Stage.ActorStarted))
        assertEquals(20L, report.totalNanos)
    }

    @Test fun backwardClockDisablesFutureTimingsWithoutClampingOrLosingPartialEvidence() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 130L
        timing.mark(Stage.ActorStarted)
        clock.now = 120L
        assertFalse(timing.mark(Stage.CursorCaptured))
        val reads = clock.reads
        clock.now = 200L
        assertFalse(timing.mark(Stage.CallbackCompleted))
        assertTrue(timing.recordCounts(queueEntries = 8))
        val report = timing.finish(Outcome.Failure)!!
        assertFalse(report.clockValid)
        assertNull(report.totalNanos)
        assertEquals(30L, report.elapsedNanos(Stage.ActorStarted))
        assertNull(report.elapsedNanos(Stage.CursorCaptured))
        assertNull(report.elapsedNanos(Stage.CallbackCompleted))
        assertEquals(8, report.counts.queueEntries)
        assertEquals(reads, clock.reads)
    }

    @Test fun backwardTerminalClockLeavesTotalAbsentAndStillFinishesOnce() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 140L
        timing.mark(Stage.CallbackCompleted)
        clock.now = 130L
        val report = timing.finish(Outcome.Success)!!
        assertFalse(report.clockValid)
        assertNull(report.totalNanos)
        assertEquals(40L, report.elapsedNanos(Stage.CallbackCompleted))
        assertNull(timing.finish(Outcome.Failure))
    }

    @Test fun elapsedOutsideSupportedSignedRangeIsAbsentNotNegative() {
        val clock = Clock(0L)
        val timing = QueueOrderTiming(clock::read)
        clock.now = Long.MIN_VALUE
        val report = timing.finish(Outcome.Failure)!!
        assertFalse(report.clockValid)
        assertNull(report.totalNanos)
        assertEquals(0L, report.elapsedNanos(Stage.Accepted))
    }

    @Test fun clockExceptionAtAcceptanceDoesNotPreventOutcomeAndCountsReport() {
        val clock = Clock().apply { failure = IllegalStateException("Clock unavailable") }
        val timing = QueueOrderTiming(clock::read)
        assertFalse(timing.mark(Stage.ActorStarted))
        timing.recordCounts(queueEntries = 3)
        val report = timing.finish(Outcome.Failure)!!
        assertFalse(report.clockValid)
        assertNull(report.totalNanos)
        Stage.entries.forEach { assertNull(report.elapsedNanos(it)) }
        assertEquals(3, report.counts.queueEntries)
        assertEquals(1, clock.reads)
    }

    @Test fun clockExceptionAtMilestonePreservesEarlierTimingsAndDoesNotEscape() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.now = 115L
        timing.mark(Stage.ActorStarted)
        clock.failure = IllegalStateException("Clock unavailable")
        assertFalse(timing.mark(Stage.CursorCaptured))
        val report = timing.finish(Outcome.Failure)!!
        assertFalse(report.clockValid)
        assertEquals(15L, report.elapsedNanos(Stage.ActorStarted))
        assertNull(report.elapsedNanos(Stage.CursorCaptured))
        assertNull(report.totalNanos)
    }

    @Test fun clockExceptionAtFinishStillReturnsExactlyOneTerminalReport() {
        val clock = Clock()
        val timing = QueueOrderTiming(clock::read)
        clock.failure = IllegalStateException("Clock unavailable")
        val report = timing.finish(Outcome.Cancelled)!!
        assertEquals(Outcome.Cancelled, report.outcome)
        assertFalse(report.clockValid)
        assertNull(report.totalNanos)
        assertNull(timing.finish(Outcome.Success))
        assertEquals(2, clock.reads)
    }
}
