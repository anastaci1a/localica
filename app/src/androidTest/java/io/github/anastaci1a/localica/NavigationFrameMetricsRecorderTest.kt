package io.github.anastaci1a.localica

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real isolated ComponentActivity Window delivery; not collector arithmetic or phone performance. */
class NavigationFrameMetricsRecorderTest {
    // Already declared by the existing debug ui-test-manifest dependency.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualHardwareWindowDeliversPrimitiveFramesInTheCompatibleClockDomain() = withWindow { window, view ->
        val start = NavigationFrameMetricsRecorder.markNanos()
        NavigationFrameMetricsRecorder.open(window, capacity = 64).use { recorder ->
            drawBurst(view)
            assertTrue("Observe a real Window callback, not just a queue barrier", recorder.awaitFrameAtOrAfter(start))
            val end = NavigationFrameMetricsRecorder.markNanos()
            val result = recorder.finish(start, end)
            assertStopped(result, recorder)
            val snapshot = requireNotNull(result.snapshot)
            assertTrue(snapshot.inWindowCount > 0)
            assertEquals(Build.VERSION.SDK_INT >= 31, snapshot.deadlineMetricsSupported)
            for (index in 0 until snapshot.storedCount) {
                val frame = snapshot[index]
                assertTrue(frame.totalDurationNanos >= 0)
                assertTrue("Callback receipt follows its VSYNC in the same clock domain",
                    frame.callbackArrivalNanos >= frame.frameTimestampNanos)
                if (Build.VERSION.SDK_INT < 31) {
                    assertNull(frame.deadlineDurationNanos)
                    assertNull(frame.exceedsReportedDeadline)
                } else frame.deadlineDurationNanos?.let { assertTrue(it >= 0) }
            }
            // This source stage has no reviewed native-tail completeness witness.
            assertEquals(NavigationFrameWindow.TailStatus.INCOMPLETE, snapshot.tailStatus)
            assertTrue(snapshot.hasQualityFailure)
        }
    }

    @Test fun removingOneListenerLeavesAnotherRealRecorderActiveAndItsSnapshotStable() = withWindow { window, view ->
        val start = NavigationFrameMetricsRecorder.markNanos()
        NavigationFrameMetricsRecorder.open(window, capacity = 128).use { first ->
            NavigationFrameMetricsRecorder.open(window, capacity = 128).use { second ->
                drawBurst(view)
                assertTrue(first.awaitFrameAtOrAfter(start))
                assertTrue(second.awaitFrameAtOrAfter(start))
                val firstResult = first.finish(start, NavigationFrameMetricsRecorder.markNanos())
                assertStopped(firstResult, first)
                val frozen = requireNotNull(firstResult.snapshot)
                val values = (0 until frozen.storedCount).map { frozen[it] }
                first.close() // Idempotent; it must not remove another recorder's listener.

                val afterRemoval = NavigationFrameMetricsRecorder.markNanos()
                drawBurst(view)
                assertTrue("The second native listener survives the first one's teardown",
                    second.awaitFrameAtOrAfter(afterRemoval))
                val secondResult = second.finish(start, NavigationFrameMetricsRecorder.markNanos())
                assertStopped(secondResult, second)
                val continuing = requireNotNull(secondResult.snapshot)
                assertTrue((0 until continuing.storedCount).any { continuing[it].frameTimestampNanos >= afterRemoval })
                assertEquals(values, (0 until frozen.storedCount).map { frozen[it] })
                assertFalse(first.isWorkerAlive)
                // Real post-close draws exercise teardown; they do not prove a
                // particular late native callback was actually delivered to first.
            }
        }
    }

    @Test fun bodyFailureRemainsPrimaryWhileTheRealListenerAndThreadAreClosed() = withWindow { window, view ->
        val recorder = NavigationFrameMetricsRecorder.open(window, capacity = 64)
        val primary = AssertionError("original test-body failure")
        val caught = runCatching {
            recorder.use {
                val start = NavigationFrameMetricsRecorder.markNanos()
                drawBurst(view)
                assertTrue(recorder.awaitFrameAtOrAfter(start))
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, caught)
        assertTrue("Ordinary cleanup itself succeeded", primary.suppressed.isEmpty())
        assertStopped(requireNotNull(recorder.lastResult), recorder)
        assertTrue(requireNotNull(recorder.lastResult?.snapshot).storedCount > 0)
        recorder.close()
    }

    @Test fun invalidWindowBoundsLeaveTheRecorderAvailableForExplicitCleanup() = withWindow { window, view ->
        val start = NavigationFrameMetricsRecorder.markNanos()
        NavigationFrameMetricsRecorder.open(window, capacity = 64).use { recorder ->
            assertTrue(runCatching { recorder.finish(start, start) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { recorder.finish(start, start - 1) }.exceptionOrNull() is IllegalArgumentException)
            drawBurst(view)
            assertTrue(recorder.awaitFrameAtOrAfter(start))
            val result = recorder.finish(start, NavigationFrameMetricsRecorder.markNanos())
            assertStopped(result, recorder)
            assertTrue(requireNotNull(result.snapshot).inWindowCount > 0)
        }
    }

    @Test fun secondaryTeardownFailuresAreSuppressedWithoutSelfSuppression() {
        // Pure bookkeeping contract, explicitly not a simulated FrameMetrics callback.
        val primary = AssertionError("body")
        val removal = IllegalStateException("remove")
        val drain = IllegalStateException("drain")
        assertSame(primary, NavigationFrameMetricsRecorder.mergeFailure(primary, removal))
        assertSame(primary, NavigationFrameMetricsRecorder.mergeFailure(primary, drain))
        assertSame(primary, NavigationFrameMetricsRecorder.mergeFailure(primary, primary))
        assertArrayEquals(arrayOf<Throwable>(removal, drain), primary.suppressed)
        assertSame(removal, NavigationFrameMetricsRecorder.mergeFailure(null, removal))
    }

    private fun assertStopped(result: NavigationFrameMetricsRecorder.Result, recorder: NavigationFrameMetricsRecorder) {
        assertTrue(result.listenerRemovalConfirmed)
        assertTrue(result.handlerTerminalReached)
        assertTrue(result.workerStopped)
        assertFalse(recorder.isWorkerAlive)
        assertEquals(0L, result.invalidCallbacksAtReturn)
        // Rejected/unknown late delivery is not silently called a complete tail.
        assertTrue(requireNotNull(result.snapshot).incompleteTail)
    }

    private fun withWindow(block: (Window, DrawView) -> Unit) {
        val view = DrawView(compose.activity)
        compose.runOnUiThread { compose.activity.setContentView(view) }
        try {
            assertTrue("Isolated native View must attach and draw", view.firstDraw.await(5, TimeUnit.SECONDS))
            block(compose.activity.window, view)
        } finally { compose.runOnUiThread { view.stop() } }
    }

    private fun drawBurst(view: DrawView) = compose.runOnUiThread { view.drawFrames(8) }

    private class DrawView(context: Context) : View(context) {
        val firstDraw = CountDownLatch(1)
        private var remaining = 0
        private var blue = false
        fun drawFrames(count: Int) { remaining = count; invalidate() }
        fun stop() { remaining = 0 }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            blue = !blue
            canvas.drawColor(if (blue) Color.BLUE else Color.RED)
            firstDraw.countDown()
            if (remaining > 0) {
                remaining--
                if (remaining > 0) postInvalidateOnAnimation()
            }
        }
    }
}
