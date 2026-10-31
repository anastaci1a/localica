package io.github.anastaci1a.localica

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Public-API adapter for one attached hardware-rendered Window, not a presentation
 * or input-latency probe. Open/await/finish/close on one non-main control thread;
 * window registration/removal runs on main, collector ownership on a private worker.
 * Use Kotlin's Closeable.use block so a body failure remains primary during teardown.
 *
 * Window bounds and arrival stamps must use [markNanos] (System.nanoTime), the
 * Choreographer timebase also used by FrameMetrics.VSYNC_TIMESTAMP. Never use
 * elapsedRealtimeNanos here. FrameMetrics values are copied during the real
 * callback; the reused framework object never escapes it.
 *
 * https://developer.android.com/reference/android/view/FrameMetrics#VSYNC_TIMESTAMP
 * https://developer.android.com/reference/android/view/Choreographer.FrameCallback
 */
internal class NavigationFrameMetricsRecorder private constructor(
    private val window: Window,
    private val capacity: Int,
    private val timeoutMillis: Long,
    private val beforeFirstTraversal: Boolean = false,
    private val registrationQueued: (() -> Unit)? = null
) : Closeable {
    companion object {
        fun markNanos(): Long = System.nanoTime()

        fun open(window: Window, capacity: Int = 4096, timeoutMillis: Long = 5000): NavigationFrameMetricsRecorder =
            open(window, capacity, timeoutMillis, beforeFirstTraversal = false, registrationQueued = null)

        /**
         * Cold-launch variant: registers on a created but not yet attached Window so the first
         * traversal is observed. View keeps the listener pending until attach. The main thread must
         * still be inside the launch transaction (for example blocked in onActivityCreated until
         * [registrationQueued] runs); the registration is queued at the front of the main queue,
         * ahead of the first traversal.
         */
        fun openBeforeFirstTraversal(window: Window, capacity: Int = 4096, timeoutMillis: Long = 5000,
            registrationQueued: () -> Unit): NavigationFrameMetricsRecorder =
            open(window, capacity, timeoutMillis, beforeFirstTraversal = true, registrationQueued = registrationQueued)

        private fun open(window: Window, capacity: Int, timeoutMillis: Long, beforeFirstTraversal: Boolean,
            registrationQueued: (() -> Unit)?): NavigationFrameMetricsRecorder {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Do not block the window/main thread" }
            require(capacity in 1..NavigationFrameWindow.MAX_CAPACITY)
            require(timeoutMillis in 1..30_000)
            val recorder = NavigationFrameMetricsRecorder(window, capacity, timeoutMillis, beforeFirstTraversal, registrationQueued)
            try {
                recorder.start()
                return recorder
            } catch (failure: Throwable) {
                recorder.shutdown(null, failure)
                throw failure
            }
        }

        /** Also tested separately: this is exception bookkeeping, not fabricated Window delivery. */
        internal fun mergeFailure(primary: Throwable?, next: Throwable): Throwable {
            if (primary == null) return next
            if (primary !== next) primary.addSuppressed(next)
            return primary
        }
    }

    data class Result(
        val snapshot: NavigationFrameWindow.Snapshot?,
        val listenerRemovalConfirmed: Boolean,
        val handlerTerminalReached: Boolean,
        val workerStopped: Boolean,
        val callbacksRejectedAtReturn: Long,
        val invalidCallbacksAtReturn: Long,
        val listenerDropsNotRecordedAtReturn: Long
    )

    private val controlThread = Thread.currentThread()
    private val openedAt = markNanos()
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private val registrationAttempted = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private class FrameWait(val minimumTimestamp: Long, val signal: CountDownLatch = CountDownLatch(1))
    private val frameWait = AtomicReference<FrameWait?>()
    private val latestFrameTimestamp = AtomicLong(Long.MIN_VALUE)
    private val observedValidFrame = AtomicBoolean(false)
    private val startupFailure = AtomicReference<Throwable?>()
    private val callbackFailure = AtomicReference<Throwable?>()
    private val rejected = AtomicLong()
    private val invalid = AtomicLong()
    private val unrecordedDrops = AtomicLong()
    private val terminalReached = AtomicBoolean(false)
    private val completedSnapshot = AtomicReference<NavigationFrameWindow.Snapshot?>()
    @Volatile private var handler: Handler? = null
    private var collector: NavigationFrameWindow? = null // Worker only.
    private var accepting = true // Worker only, sealed before collector finalization.
    private var closed = false // Control thread only.
    var lastResult: Result? = null
        private set
    val isWorkerAlive: Boolean get() = worker.isAlive

    private val worker = object : HandlerThread("localica-navigation-frame-metrics") {
        override fun onLooperPrepared() {
            try {
                handler = Handler(looper)
                collector = NavigationFrameWindow(capacity, Build.VERSION.SDK_INT >= 31)
            } catch (failure: Throwable) {
                startupFailure.set(failure)
                looper.quitSafely()
            } finally {
                // A setup timeout can precede looper creation. Never call getLooper
                // from the control thread while waiting for that creation.
                if (stopping.get()) { accepting = false; looper.quitSafely() }
                ready.countDown()
            }
        }
    }

    private val listener = Window.OnFrameMetricsAvailableListener { reportedWindow, metrics, drops ->
        try {
            check(Looper.myLooper() === handler?.looper) { "Frame callback escaped the owned worker" }
            if (!accepting) {
                rejected.incrementAndGet()
                if (drops > 0) unrecordedDrops.addAndGet(drops.toLong())
            } else {
                check(reportedWindow === window) { "Unexpected frame Window" }
                val arrival = markNanos()
                val timestamp = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE)
                    else NavigationFrameWindow.UNAVAILABLE
                check(timestamp != -1L && duration >= 0 && deadline >= -1L && drops >= 0) {
                    "Unavailable/invalid required frame metrics: vsync=$timestamp total=$duration deadline=$deadline drops=$drops"
                }
                requireNotNull(collector).record(timestamp, arrival, duration, deadline, drops)
                if (timestamp > latestFrameTimestamp.get()) latestFrameTimestamp.set(timestamp)
                observedValidFrame.set(true)
                frameWait.get()?.let { if (timestamp >= it.minimumTimestamp) it.signal.countDown() }
            }
        } catch (failure: Throwable) {
            // Never let a bad metric/late callback kill the looper or mutate a
            // finalized collector. Surface the first error on the control thread.
            invalid.incrementAndGet()
            if (drops > 0) unrecordedDrops.addAndGet(drops.toLong())
            callbackFailure.compareAndSet(null, failure)
            frameWait.get()?.signal?.countDown()
        }
    }

    private fun start() {
        worker.start()
        check(ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "Frame worker startup timed out" }
        startupFailure.get()?.let { throw it }
        checkNotNull(handler)
        onMain(cancelIfTimedOut = true, front = beforeFirstTraversal, queued = registrationQueued) {
            // A timed-out queued registration must never attach after shutdown.
            if (!stopping.get()) {
                if (beforeFirstTraversal) check(window.peekDecorView() != null) {
                    "Pre-traversal frame metrics require a created Window decor"
                } else check(window.decorView.isAttachedToWindow && window.decorView.isHardwareAccelerated) {
                    "Frame metrics require an attached hardware-rendered Window"
                }
                registrationAttempted.set(true)
                try {
                    window.addOnFrameMetricsAvailableListener(listener, requireNotNull(handler))
                } finally {
                    // Covers a registration already running when its caller timed out.
                    if (stopping.get()) removeOnMain()
                }
            }
        }
    }

    /** Waits for a real valid frame timestamp, not a handler barrier or performance threshold. */
    fun awaitFrameAtOrAfter(timestampNanos: Long): Boolean {
        checkControl()
        check(!closed)
        throwCallbackFailure()
        val wait = FrameWait(timestampNanos)
        check(frameWait.compareAndSet(null, wait))
        try {
            // Close the race with a callback delivered just before waiter installation.
            if (observedValidFrame.get() && latestFrameTimestamp.get() >= timestampNanos) wait.signal.countDown()
            throwCallbackFailure()
            val observed = wait.signal.await(timeoutMillis, TimeUnit.MILLISECONDS)
            throwCallbackFailure()
            return observed
        } finally { frameWait.compareAndSet(wait, null) }
    }

    fun finish(startInclusiveNanos: Long, endExclusiveNanos: Long): Result {
        checkControl()
        check(!closed)
        require(endExclusiveNanos > startInclusiveNanos)
        return shutdown(startInclusiveNanos to endExclusiveNanos, null)
    }

    override fun close() {
        checkControl()
        if (!closed) shutdown(openedAt to markNanos(), null)
    }

    private fun shutdown(bounds: Pair<Long, Long>?, primary: Throwable?): Result {
        checkControl()
        check(!closed)
        closed = true
        stopping.set(true)
        var failure = primary
        var interrupted = primary is InterruptedException
        fun attempt(block: () -> Unit) {
            try { block() } catch (next: Throwable) {
                if (next is InterruptedException) interrupted = true
                failure = mergeFailure(failure, next)
            }
        }
        var removed = false
        attempt {
            // Do not cancel timed-out cleanup: it still removes this listener if
            // the main thread later recovers. The timeout remains a fatal result.
            onMain(cancelIfTimedOut = false) { removeOnMain() }
            removed = !registrationAttempted.get()
        }
        val ownerHandler = handler
        if (ownerHandler != null) attempt {
            val terminal = FutureTask<Unit> {
                accepting = false
                terminalReached.set(true)
                if (bounds != null) completedSnapshot.set(requireNotNull(collector).finish(
                    bounds.first, bounds.second, NavigationFrameWindow.TailStatus.INCOMPLETE))
            }
            check(ownerHandler.post(terminal)) { "Frame worker rejected finalization" }
            awaitTask(terminal)
        }
        // quitSafely handles due queued work only. No barrier or unregister can
        // certify all native delivery; every returned snapshot keeps INCOMPLETE.
        attempt { handler?.looper?.quitSafely() }
        attempt {
            worker.join(timeoutMillis)
            check(!worker.isAlive) { "Frame worker did not stop within the teardown bound" }
        }
        callbackFailure.get()?.let { failure = mergeFailure(failure,
            IllegalStateException("Native frame callback failed during recording", it)) }
        val result = Result(completedSnapshot.get(), removed, terminalReached.get(), !worker.isAlive,
            rejected.get(), invalid.get(), unrecordedDrops.get())
        lastResult = result
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
        return result
    }

    private fun removeOnMain() {
        check(Looper.myLooper() === Looper.getMainLooper())
        if (registrationAttempted.get()) {
            window.removeOnFrameMetricsAvailableListener(listener)
            registrationAttempted.set(false)
        }
    }

    private fun onMain(cancelIfTimedOut: Boolean, front: Boolean = false, queued: (() -> Unit)? = null, block: () -> Unit) {
        val task = FutureTask<Unit> { block() }
        check(if (front) main.postAtFrontOfQueue(task) else main.post(task)) { "Main thread rejected frame-listener lifecycle operation" }
        queued?.invoke()
        try { awaitTask(task) } catch (failure: Throwable) {
            if (cancelIfTimedOut) task.cancel(false)
            throw failure
        }
    }

    private fun awaitTask(task: FutureTask<Unit>) {
        try { task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
    }

    private fun checkControl() {
        check(Thread.currentThread() === controlThread && Looper.myLooper() != Looper.getMainLooper()) {
            "Recorder lifecycle belongs to its non-main control thread"
        }
    }

    private fun throwCallbackFailure() {
        // Do not throw the same object from both the body wait and close(): use{}
        // must be able to suppress a teardown error without self-suppression.
        callbackFailure.get()?.let { throw IllegalStateException("Native frame callback failed during recording", it) }
    }
}
