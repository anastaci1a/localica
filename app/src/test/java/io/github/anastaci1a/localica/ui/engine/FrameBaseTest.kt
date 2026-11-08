package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** Regression for the ProvideUiClock time base: a construction-time System.nanoTime origin made the first virtual
 * (test) frame fail "Frame source must advance monotonically", and could on a janky first real frame. */
class FrameBaseTest {
    private var system=5_000_000_000_000L
    private val base=FrameBase { system }
    private val motion=object: ActiveMotion { override val id=1L; override val presented=true; override val settled=false
        override fun endsAt(stamp: FrameStamp)=false; override fun finish(stamp: FrameStamp) {}; override fun reduce(stamp: FrameStamp) {} }
    /** Drives a UiClock exactly as ProvideUiClock wires it: every source frame is first recorded by [base]. */
    private fun presented(vararg frames: Long): List<FrameStamp> {
        val channel=Channel<Long>(Channel.UNLIMITED); val stamps=mutableListOf<FrameStamp>()
        val clock=UiClock(object: FrameSource { override suspend fun awaitFrame()=channel.receive().also(base::frame) },base::now,{})
        clock.configure(EngineContext(1f/120,1000f,MotionAccess.Standard),StockUiSpec)
        val scope=CoroutineScope(Dispatchers.Unconfined+SupervisorJob()); val failure=CompletableDeferred<Throwable?>()
        scope.launch { failure.complete(runCatching { clock.run() }.exceptionOrNull()) }
        clock.register(motion)
        for(f in frames) { channel.trySend(f); stamps+=clock.presentedStamp; assertFalse(failure.isCompleted) }
        scope.cancel(); return stamps
    }
    @Test fun beforeTheFirstFrameIntentsPrecedeAnyFrame() {
        assertEquals(Long.MIN_VALUE/4,base.now())
    }
    @Test fun firstVirtualFrameAfterConstructionIsMonotonic() {
        // A virtual clock starts near zero while System.nanoTime is large: the old origin (construction time) exceeded it.
        val stamps=presented(16_000_000L,32_000_000L,48_000_000L)
        assertEquals(listOf(16_000_000L,32_000_000L,48_000_000L),stamps.map { it.nanos })
    }
    @Test fun firstRealFrameEarlierThanConstructionIsMonotonic() {
        // Choreographer vsync timestamps can precede the clock's construction (janky first frame).
        val stamps=presented(system-40_000_000L,system-23_000_000L)
        assertEquals(listOf(system-40_000_000L,system-23_000_000L),stamps.map { it.nanos })
    }
    @Test fun realFramesStampIntentsWithSystemTime() {
        base.frame(system-8_000_000L); system+=3_000_000L
        assertEquals(system,base.now())
        system+=1_000_000L; assertEquals(system,base.now())
    }
    @Test fun virtualFramesStampIntentsAtTheLastFrame() {
        base.frame(16_000_000L); system+=5_000_000L
        assertEquals(16_000_000L,base.now())
        base.frame(32_000_000L); assertEquals(32_000_000L,base.now())
    }
    @Test fun intentTimeNeverRegresses() {
        base.frame(system); system+=10_000_000L; val real=base.now()
        base.frame(16_000_000L) // switch to a virtual base (e.g. a test clock takes over)
        assertEquals(real,base.now())
    }
    @Test fun aFrameIsPresentedInsideItsFrameCallback() {
        // Compose recomposes right after the frame callbacks; a stamp written after awaitFrame returns lags one frame.
        val channel=Channel<Long>(Channel.UNLIMITED); var clock: UiClock? = null; var seen=Long.MIN_VALUE
        clock=UiClock(object: FrameSource { override suspend fun awaitFrame()=channel.receive()
            override suspend fun <R> awaitFrame(onFrame: (Long)->R): R { val nanos=channel.receive(); return onFrame(nanos).also { seen=clock!!.stamp.nanos } } },{ 0L },{})
        clock.configure(EngineContext(1f/120,1000f,MotionAccess.Standard),StockUiSpec)
        val scope=CoroutineScope(Dispatchers.Unconfined+SupervisorJob()); scope.launch { clock.run() }
        clock.register(motion); channel.trySend(16_000_000L)
        assertEquals(16_000_000L,seen); scope.cancel()
    }
}
