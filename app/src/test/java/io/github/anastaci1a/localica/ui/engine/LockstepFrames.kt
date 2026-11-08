package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

/** One virtual frame clock shared by a legacy Compose reference (through [MonotonicFrameClock]) and the Engine root
 * ([EngineHarness]): every tick delivers the same frame nanos to both, so values can be compared frame for frame. */
internal class LockstepFrames(val h: EngineHarness) : MonotonicFrameClock {
    private val waiters = mutableListOf<CancellableContinuation<Long>>()
    val release = registerScrollClock(this, h.clock)
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val nanos = suspendCancellableCoroutine<Long> { waiters += it }
        return onFrame(nanos)
    }
    /** Advances both clocks by [millis]: the root samples its channels inside the tick; legacy callbacks resume on yield.
     * Like the Choreographer, a frame reaches the root only while it awaits one (idle frames are not buffered). */
    suspend fun tick(millis: Double) {
        h.now += (millis * 1_000_000).toLong()
        if (h.clock.activeCount > 0) h.advance(0.0)
        h.failures.firstOrNull()?.let { throw AssertionError("Root clock failed: $it", it) }
        val pending = waiters.toList(); waiters.clear()
        pending.forEach { it.resume(h.now) }
        Snapshot.sendApplyNotifications()
        yield(); yield()
        Snapshot.sendApplyNotifications()
        yield()
    }
}
