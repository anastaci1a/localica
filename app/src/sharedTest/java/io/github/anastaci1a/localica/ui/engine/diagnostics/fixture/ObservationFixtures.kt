package io.github.anastaci1a.localica.ui.engine.diagnostics.fixture

import kotlin.coroutines.*

/** Stage0 test stand-ins only. U12.01b binds diagnostics to real U01/U02 types, then U03/U04.
 * None of these declarations is in main/debug or installed in the application. */
internal data class FrameStamp(val nanos: Long, val sequence: Long) {
    init { require(nanos >= 0 && sequence >= 0) }
}
internal data class Kinematic(val value: Float, val velocity: Float)
internal interface MotionValue {
    val target: Float
    fun value(): Float
    fun sampleAt(stamp: FrameStamp): Kinematic
}
internal data class ActiveMotion(val id: Long, val presented: Boolean = false)
internal fun interface FrameObserver { fun onFrame(stamp: FrameStamp, active: List<ActiveMotion>) }
internal data class HostKey(val id: String, val epoch: Long)
/** Independent Double reference maps, not a substitute production Engine Curve implementation. */
internal interface Curve { fun at(p: Double): Double; fun slope(p: Double): Double }
internal class OwnerThread {
    private val thread = Thread.currentThread()
    fun check() { check(Thread.currentThread() === thread) { "Observation must remain on its installing thread" } }
}
internal class ManualFrameSource(private val owner: OwnerThread = OwnerThread()) : AutoCloseable {
    private var waiter: Continuation<Long>? = null
    private var stamp = FrameStamp(0, 0)
    private var closed = false
    var awaits = 0L; private set
    suspend fun awaitFrame(): Long = suspendCoroutine { continuation ->
        owner.check(); check(!closed && waiter == null); awaits++; waiter = continuation
    }
    fun advance(nanos: Long): FrameStamp {
        owner.check(); check(!closed); require(nanos > stamp.nanos)
        stamp = FrameStamp(nanos, stamp.sequence + 1)
        val pending = waiter; waiter = null; pending?.resume(nanos)
        return stamp
    }
    override fun close() {
        owner.check(); if (closed) return; closed = true
        val pending = waiter; waiter = null; pending?.resumeWithException(IllegalStateException("Frame source closed"))
    }
}
