package io.github.anastaci1a.localica

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pure receipt contracts; these records are not fabricated native callback evidence. */
class NavigationFrameDiagnosticWindowTest {
    private val protocol = NavigationFrameDiagnosticWindow.Protocol(
        NavigationFrameDiagnosticWindow.Clock.SYSTEM_NANOTIME_VSYNC, 9L, 10L, 20L, 21L, true, true)

    @Test fun witnessedSubsetIsExplicitlyIncompleteAndNeverCompleteWindowMeasurement() {
        val receipt = inspect()
        assertEquals(NavigationFrameDiagnosticWindow.ObservationStatus.INCOMPLETE_RECEIVED_SUBSET_PROTOCOL_OBSERVED,
            receipt.observationStatus)
        assertTrue(receipt.rejections.isEmpty())
        assertEquals(NavigationFrameWindow.TailStatus.INCOMPLETE, receipt.tailStatus)
        assertFalse(receipt.completeWindowMeasurement)
        assertEquals(20L, requireNotNull(receipt.retainedEndWitness).frameTimestampNanos)
        assertEquals(24L, requireNotNull(receipt.retainedEndWitness).callbackArrivalNanos)
        assertEquals(1, requireNotNull(receipt.retainedSubsetCounts).inWindow)
        assertSame(protocol, receipt.protocol)
    }

    @Test fun waiterSuccessCannotReplaceARetainedWitnessEvenWhenOverflowObservedIt() {
        val buffer = NavigationFrameWindow(1, false)
        buffer.record(12, 15, 3)
        assertFalse(buffer.record(20, 24, 4))
        val receipt = inspect(result(buffer.finish(10, 20)))
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.BUFFER_OVERFLOW,
            NavigationFrameDiagnosticWindow.Rejection.MISSING_RETAINED_END_WITNESS)
        assertNull(receipt.retainedEndWitness)
        assertEquals(2L, requireNotNull(receipt.recorderResult?.snapshot).receivedCount)
        assertEquals(1L, requireNotNull(receipt.recorderResult?.snapshot).bufferOverflowCount)
    }

    @Test fun missingResultMissingSnapshotAndEmptySubsetRemainDistinctUnavailableEvidence() {
        val missing = NavigationFrameDiagnosticWindow.inspect(protocol, null)
        assertRejected(missing, NavigationFrameDiagnosticWindow.Rejection.MISSING_RESULT,
            NavigationFrameDiagnosticWindow.Rejection.MISSING_SNAPSHOT)
        assertNull(missing.retainedSubsetCounts)
        val noSnapshot = inspect(result(null))
        assertRejected(noSnapshot, NavigationFrameDiagnosticWindow.Rejection.MISSING_SNAPSHOT)
        assertFalse(noSnapshot.rejections.contains(NavigationFrameDiagnosticWindow.Rejection.MISSING_RESULT))
        assertNull(noSnapshot.retainedSubsetCounts)
        val empty = inspect(result(snapshot(emptyList())))
        assertRejected(empty, NavigationFrameDiagnosticWindow.Rejection.EMPTY_IN_WINDOW_SUBSET,
            NavigationFrameDiagnosticWindow.Rejection.MISSING_RETAINED_END_WITNESS)
        assertEquals(0, requireNotNull(empty.retainedSubsetCounts).inWindow)
        assertNull(requireNotNull(empty.retainedSubsetCounts).durationExceedsReportedDeadlineFraction)
        val onlyAfter = inspect(result(snapshot(listOf(20L))))
        assertRejected(onlyAfter, NavigationFrameDiagnosticWindow.Rejection.EMPTY_IN_WINDOW_SUBSET)
        assertNotNull(onlyAfter.retainedEndWitness)
    }

    @Test fun everyIndependentLossAndTeardownFlagRejectsEvenWithARealRetainedWitness() {
        val clean = result(snapshot())
        val cases = listOf(
            clean.copy(callbacksRejectedAtReturn = 1) to NavigationFrameDiagnosticWindow.Rejection.CALLBACKS_REJECTED,
            clean.copy(invalidCallbacksAtReturn = 1) to NavigationFrameDiagnosticWindow.Rejection.INVALID_CALLBACKS,
            clean.copy(listenerDropsNotRecordedAtReturn = 1) to NavigationFrameDiagnosticWindow.Rejection.UNRECORDED_LISTENER_DROPS,
            clean.copy(listenerRemovalConfirmed = false) to NavigationFrameDiagnosticWindow.Rejection.LISTENER_REMOVAL_UNCONFIRMED,
            clean.copy(handlerTerminalReached = false) to NavigationFrameDiagnosticWindow.Rejection.HANDLER_TERMINAL_UNREACHED,
            clean.copy(workerStopped = false) to NavigationFrameDiagnosticWindow.Rejection.WORKER_NOT_STOPPED,
            clean.copy(callbacksRejectedAtReturn = -1) to NavigationFrameDiagnosticWindow.Rejection.INVALID_COUNTERS
        )
        for ((input, reason) in cases) {
            val receipt = inspect(input)
            assertRejected(receipt, reason)
            assertSame(input, receipt.recorderResult)
            assertNotNull(receipt.retainedEndWitness)
        }
        val dropped = NavigationFrameWindow(4, false)
        dropped.record(12, 15, 3, listenerDroppedCount = 2)
        dropped.record(20, 24, 4)
        val receipt = inspect(result(dropped.finish(10, 20)))
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.LISTENER_REPORT_LOSS)
        assertEquals(2L, requireNotNull(receipt.recorderResult?.snapshot).reportedListenerDropCount)
        val overflow = NavigationFrameWindow(2, false)
        overflow.record(12, 15, 3); overflow.record(20, 24, 4); overflow.record(22, 25, 3)
        assertRejected(inspect(result(overflow.finish(10, 20))), NavigationFrameDiagnosticWindow.Rejection.BUFFER_OVERFLOW)
    }

    @Test fun unsuccessfulWaitAndUnfinishedDrawProtocolRejectWithoutAdaptiveRetry() {
        assertRejected(NavigationFrameDiagnosticWindow.inspect(protocol.copy(waiterSucceeded = false), result(snapshot())),
            NavigationFrameDiagnosticWindow.Rejection.WAITER_UNSUCCESSFUL)
        assertRejected(NavigationFrameDiagnosticWindow.inspect(protocol.copy(postEndDrawsCompleted = false), result(snapshot())),
            NavigationFrameDiagnosticWindow.Rejection.POST_END_DRAWS_UNFINISHED)
    }

    @Test fun unknownClockLateRegistrationAndChangedOrInvalidBoundsReject() {
        val bad = listOf(
            protocol.copy(registrationCompletedNanos = 11),
            protocol.copy(startInclusiveNanos = 20),
            protocol.copy(endExclusiveNanos = 9),
            protocol.copy(postEndDrawRequestedNanos = 19),
            protocol.copy(postEndDrawRequestedNanos = null)
        )
        for (input in bad) assertRejected(NavigationFrameDiagnosticWindow.inspect(input, result(snapshot())),
            NavigationFrameDiagnosticWindow.Rejection.INVALID_PHASE_ORDER)
        assertRejected(NavigationFrameDiagnosticWindow.inspect(protocol.copy(clock = NavigationFrameDiagnosticWindow.Clock.UNKNOWN),
            result(snapshot())), NavigationFrameDiagnosticWindow.Rejection.UNKNOWN_CLOCK)
        assertRejected(NavigationFrameDiagnosticWindow.inspect(protocol.copy(endExclusiveNanos = 19), result(snapshot())),
            NavigationFrameDiagnosticWindow.Rejection.SNAPSHOT_BOUNDS_MISMATCH)
    }

    @Test fun unavailableVsyncAndArrivalBeforeVsyncRejectWithoutSortingRecords() {
        assertRejected(inspect(result(snapshot(listOf(-1, 12, 20)))), NavigationFrameDiagnosticWindow.Rejection.INVALID_FRAME_CLOCK)
        val invalid = NavigationFrameWindow(4, false)
        invalid.record(12, 11, 3); invalid.record(20, 24, 4)
        assertRejected(inspect(result(invalid.finish(10, 20))), NavigationFrameDiagnosticWindow.Rejection.INVALID_FRAME_CLOCK)
    }

    @Test fun duplicateAndOutOfOrderTimestampsRemainDistinctRetainedRecords() {
        val buffer = NavigationFrameWindow(5, false)
        buffer.record(20, 24, 4); buffer.record(12, 25, 3)
        buffer.record(12, 26, 3); buffer.record(9, 27, 3)
        val receipt = inspect(result(buffer.finish(10, 20)))
        assertTrue(receipt.rejections.isEmpty())
        val counts = requireNotNull(receipt.retainedSubsetCounts)
        assertEquals(1, counts.beforeWindow); assertEquals(2, counts.inWindow)
        assertEquals(1, counts.afterWindow); assertEquals(2, counts.lateInWindow)
        assertEquals(1, counts.duplicateTimestampRecords)
        assertEquals(listOf(20L, 12L, 12L, 9L), (0 until 4).map { requireNotNull(receipt.recorderResult?.snapshot)[it].frameTimestampNanos })
    }

    @Test fun deadlineDenominatorIncludesOnlyAvailableInWindowRecordsAndUsesStrictComparison() {
        val buffer = NavigationFrameWindow(8, true)
        buffer.record(9, 11, 8, 4) // Outside: not a deadline denominator member.
        buffer.record(11, 15, 3, 4)
        buffer.record(12, 16, 4, 4) // Equal does not exceed.
        buffer.record(13, 18, 5, 4)
        buffer.record(14, 19, 5) // Supported but unavailable.
        buffer.record(20, 24, 8, 4) // Witness is also outside.
        val receipt = inspect(result(buffer.finish(10, 20)))
        val counts = requireNotNull(receipt.retainedSubsetCounts)
        assertTrue(receipt.rejections.isEmpty())
        assertEquals(4, counts.inWindow); assertEquals(3, counts.deadlineEligibleInWindow)
        assertEquals(1, counts.deadlineUnavailableInWindow)
        assertEquals(1, counts.durationExceedsReportedDeadlineInWindow)
        assertEquals(1.0 / 3.0, requireNotNull(counts.durationExceedsReportedDeadlineFraction), 0.0)
        for (supported in listOf(false, true)) {
            val missing = requireNotNull(inspect(result(snapshot(supported = supported))).retainedSubsetCounts)
            assertEquals(supported, missing.deadlineMetricsSupported)
            assertEquals(0, missing.deadlineEligibleInWindow)
            assertEquals(1, missing.deadlineUnavailableInWindow)
            assertNull(missing.durationExceedsReportedDeadlineFraction)
        }
    }

    @Test fun unexpectedSourceTailCannotBePromotedToCompleteOrAcceptedAsThisProtocol() {
        val buffer = NavigationFrameWindow(4, false)
        buffer.record(12, 15, 3); buffer.record(20, 24, 4)
        val receipt = inspect(result(buffer.finish(10, 20, NavigationFrameWindow.TailStatus.BOUNDED_DRAIN_FINISHED)))
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.UNEXPECTED_SOURCE_TAIL)
        assertEquals(NavigationFrameWindow.TailStatus.BOUNDED_DRAIN_FINISHED, requireNotNull(receipt.recorderResult?.snapshot).tailStatus)
        assertEquals(NavigationFrameWindow.TailStatus.INCOMPLETE, receipt.tailStatus)
    }

    @Test fun failureReceiptPreservesBodyAndTeardownOwnershipWithoutMutatingExceptions() {
        val body = AssertionError("body")
        val teardown = IllegalStateException("teardown")
        val partial = result(snapshot()).copy(workerStopped = false)
        val receipt = NavigationFrameDiagnosticWindow.inspect(protocol, partial, body, teardown)
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.PROTOCOL_EXCEPTION,
            NavigationFrameDiagnosticWindow.Rejection.TEARDOWN_EXCEPTION, NavigationFrameDiagnosticWindow.Rejection.WORKER_NOT_STOPPED)
        assertSame(body, receipt.primaryFailure); assertSame(body, receipt.protocolFailure)
        assertSame(teardown, receipt.teardownFailure); assertSame(partial, receipt.recorderResult)
        assertTrue(body.suppressed.isEmpty()); assertTrue(teardown.suppressed.isEmpty())
        assertSame(teardown, NavigationFrameDiagnosticWindow.inspect(protocol, partial, teardownFailure = teardown).primaryFailure)
    }

    @Test fun rejectionReasonsAreDefensivelyCopiedAndCannotBeClearedByTheReader() {
        val reasons = mutableSetOf(NavigationFrameDiagnosticWindow.Rejection.UNKNOWN_CLOCK)
        val receipt = NavigationFrameDiagnosticWindow.Receipt(protocol, null, null, null, reasons, null, null)
        reasons.clear()
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.UNKNOWN_CLOCK)
        val mutation = runCatching { (receipt.rejections as MutableSet<*>).clear() }.exceptionOrNull()
        assertTrue(mutation is UnsupportedOperationException)
        assertRejected(receipt, NavigationFrameDiagnosticWindow.Rejection.UNKNOWN_CLOCK)
    }

    private fun inspect(input: NavigationFrameMetricsRecorder.Result = result(snapshot())) =
        NavigationFrameDiagnosticWindow.inspect(protocol, input)
    private fun result(snapshot: NavigationFrameWindow.Snapshot?) =
        NavigationFrameMetricsRecorder.Result(snapshot, true, true, true, 0, 0, 0)
    private fun snapshot(times: List<Long> = listOf(12, 20), supported: Boolean = false): NavigationFrameWindow.Snapshot {
        val buffer = NavigationFrameWindow(8, supported)
        times.forEach { buffer.record(it, it + 4, 3) }
        return buffer.finish(10, 20)
    }
    private fun assertRejected(receipt: NavigationFrameDiagnosticWindow.Receipt,
        vararg reasons: NavigationFrameDiagnosticWindow.Rejection) {
        assertEquals(NavigationFrameDiagnosticWindow.ObservationStatus.REJECTED, receipt.observationStatus)
        reasons.forEach { assertTrue("Missing $it in ${receipt.rejections}", receipt.rejections.contains(it)) }
        assertFalse(receipt.completeWindowMeasurement)
        assertEquals(NavigationFrameWindow.TailStatus.INCOMPLETE, receipt.tailStatus)
    }
}

/** One real attached-Window protocol; pure predicates above do not launch this Activity. */
class NavigationFrameDiagnosticWindowNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fixedPostEndDrawsYieldOnlyAnIncompleteReceivedSubsetReceipt() {
        val view = FixedDrawView(compose.activity)
        compose.runOnUiThread { compose.activity.setContentView(view) }
        var primaryFailure: Throwable? = null
        try {
            assertTrue("Already-attached fixture first draw", view.firstDraw.await(
                NavigationFrameDiagnosticWindow.WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            val recorder = NavigationFrameMetricsRecorder.open(compose.activity.window, capacity = 128,
                timeoutMillis = NavigationFrameDiagnosticWindow.WAIT_TIMEOUT_MILLIS)
            val registered = NavigationFrameMetricsRecorder.markNanos()
            val start = NavigationFrameMetricsRecorder.markNanos()
            var bodyFailure: Throwable? = null
            var teardownFailure: Throwable? = null
            var postEndRequested: Long? = null
            var postEndCompleted = false
            var waiterSucceeded = false
            // T1 is the fixed eight-View-draw script boundary (or its failure),
            // never selected/extended to obtain a favorable callback or witness.
            try { drawExactly(view, 8) } catch (failure: Throwable) { bodyFailure = failure }
            val end = NavigationFrameMetricsRecorder.markNanos()
            try {
                if (bodyFailure == null) {
                    postEndRequested = NavigationFrameMetricsRecorder.markNanos()
                    drawExactly(view, 2)
                    postEndCompleted = true
                    waiterSucceeded = recorder.awaitFrameAtOrAfter(end)
                }
            } catch (failure: Throwable) { bodyFailure = failure }
            finally {
                try { recorder.finish(start, end) } catch (failure: Throwable) {
                    teardownFailure = failure
                    // Invalid bounds leave the recorder open; close also preserves
                    // lastResult on normal teardown failure and is otherwise idempotent.
                    try { recorder.close() } catch (cleanup: Throwable) {
                        NavigationFrameMetricsRecorder.mergeFailure(failure, cleanup)
                    }
                }
            }
            val protocol = NavigationFrameDiagnosticWindow.Protocol(
                NavigationFrameDiagnosticWindow.Clock.SYSTEM_NANOTIME_VSYNC,
                registered, start, end, postEndRequested, postEndCompleted, waiterSucceeded)
            val receipt = NavigationFrameDiagnosticWindow.inspect(protocol, recorder.lastResult, bodyFailure, teardownFailure)
            val evidence = "${protocol.drawProtocol}, $protocol, waitBoundMs=${protocol.waitTimeoutMillis}: " +
                "status=${receipt.observationStatus}, tail=${receipt.tailStatus}, completeWindowMeasurement=false, rejected=${receipt.rejections}, " +
                "counts=${receipt.retainedSubsetCounts}, witness=${receipt.retainedEndWitness}, result=${receipt.recorderResult}"
            // Emit only after unregister/seal/join. Retain failed as well as clean
            // observations; per-record lines avoid Android's long-line truncation.
            android.util.Log.i("NavigationFrameDiagnostic", evidence)
            receipt.recorderResult?.snapshot?.let { snapshot ->
                android.util.Log.i("NavigationFrameDiagnostic", "raw bounds=[${snapshot.startInclusiveNanos},${snapshot.endExclusiveNanos}), " +
                    "received=${snapshot.receivedCount}, stored=${snapshot.storedCount}, " +
                    "listenerReportDrops=${snapshot.reportedListenerDropCount}, overflow=${snapshot.bufferOverflowCount}")
                for (index in 0 until snapshot.storedCount) android.util.Log.i("NavigationFrameDiagnostic", "record[$index]=${snapshot[index]}")
            }
            var failure = bodyFailure
            teardownFailure?.let { failure = NavigationFrameMetricsRecorder.mergeFailure(failure, it) }
            failure?.let {
                it.addSuppressed(AssertionError(evidence)) // Retain failed receipt; original failure stays primary.
                throw it
            }
            assertEquals(evidence, NavigationFrameDiagnosticWindow.ObservationStatus.INCOMPLETE_RECEIVED_SUBSET_PROTOCOL_OBSERVED,
                receipt.observationStatus)
            assertNotNull(evidence, receipt.retainedEndWitness)
            assertFalse(receipt.completeWindowMeasurement)
            assertEquals(NavigationFrameWindow.TailStatus.INCOMPLETE, receipt.tailStatus)
            assertFalse(recorder.isWorkerAlive)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { compose.runOnUiThread { view.stop() } } catch (cleanup: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanup
                NavigationFrameMetricsRecorder.mergeFailure(primary, cleanup)
            }
        }
    }

    private fun drawExactly(view: FixedDrawView, count: Int) {
        val done = CountDownLatch(count)
        compose.runOnUiThread { view.begin(done) }
        assertTrue("Fixed $count-draw protocol timed out; no retry or window extension",
            done.await(NavigationFrameDiagnosticWindow.WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    private class FixedDrawView(context: Context) : View(context) {
        val firstDraw = CountDownLatch(1)
        private var remaining: CountDownLatch? = null
        private var blue = false
        fun begin(draws: CountDownLatch) { remaining = draws; postInvalidateOnAnimation() }
        fun stop() { remaining = null }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            blue = !blue
            canvas.drawColor(if (blue) Color.BLUE else Color.RED)
            firstDraw.countDown()
            remaining?.let {
                it.countDown()
                if (it.count > 0) postInvalidateOnAnimation() else remaining = null
            }
        }
    }
}
