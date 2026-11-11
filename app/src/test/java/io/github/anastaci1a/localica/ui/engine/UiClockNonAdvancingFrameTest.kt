package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** #848: a real frame source can repeat a frame time (several awaiters served by one Choreographer frame, a vsync skip,
 * a resume) or step back. The clock must treat that as the already presented frame, never crash, and keep FrameStamp strictly monotonic. */
class UiClockNonAdvancingFrameTest {
    private val profile=motionProfileId(MotionProfile.Control)
    private class Recorder: ActiveMotion { override val id=1L; override val presented=true; override val settled=false
        val frames=mutableListOf<FrameStamp>(); val ended=mutableListOf<FrameStamp>()
        override fun frame(stamp: FrameStamp) { frames+=stamp }
        override fun endsAt(stamp: FrameStamp): Boolean { ended+=stamp; return false }
        override fun finish(stamp: FrameStamp) {}; override fun reduce(stamp: FrameStamp) {} }
    private class Rig {
        val channel=Channel<Long>(Channel.UNLIMITED); var now=0L; val observed=mutableListOf<FrameStamp>()
        val clock=UiClock(object: FrameSource { override suspend fun awaitFrame()=channel.receive() },{ now },{})
        val scope=CoroutineScope(Dispatchers.Unconfined+SupervisorJob()); val failure=CompletableDeferred<Throwable?>()
        init { clock.configure(EngineContext(1f/120,1000f,MotionAccess.Standard),StockUiSpec); clock.observers+=FrameObserver { stamp,_ -> observed+=stamp }
            scope.launch { failure.complete(runCatching { clock.run() }.exceptionOrNull()) } }
        fun deliver(vararg frames: Long) { for(f in frames) { channel.trySend(f); assertFalse("clock crashed on frame $f: ${failure.takeIf { it.isCompleted }?.getCompleted()}",failure.isCompleted) } }
        fun close() { scope.cancel() }
    }
    @Test fun equalAndBackwardsFrameTimesAreTheAlreadyPresentedFrame() {
        val rig=Rig(); val motion=Recorder(); rig.clock.register(motion)
        rig.deliver(16_000_000L,16_000_000L,8_000_000L,32_000_000L,32_000_000L,0L,48_000_000L)
        assertEquals(listOf(16_000_000L,32_000_000L,48_000_000L),motion.frames.map { it.nanos })
        assertEquals(listOf(1L,2L,3L),motion.frames.map { it.sequence })
        assertEquals(motion.frames,motion.ended); assertEquals(motion.frames,rig.observed)
        assertEquals(48_000_000L,rig.clock.presentedStamp.nanos); assertEquals(3L,rig.clock.presentedStamp.sequence); assertEquals(rig.clock.presentedStamp,rig.clock.stamp)
        assertTrue(rig.clock.stampNow.nanos>=rig.clock.presentedStamp.nanos)
        rig.close()
    }
    @Test fun aFirstFrameEqualToTheOriginIsCoalescedAndTheNextAdvances() {
        val rig=Rig(); val motion=Recorder(); rig.clock.register(motion)
        rig.deliver(0L,-5L,16_000_000L)
        assertEquals(listOf(16_000_000L),motion.frames.map { it.nanos }); assertEquals(1L,rig.clock.presentedStamp.sequence)
        rig.close()
    }
    @Test fun aMotionOwnerRunsToItsEndAcrossRepeatedAndBackwardsFrames() {
        val rig=Rig(); val m=MotionFloat(rig.clock,0f,1f); m.retarget(1f,profile); assertEquals(1,rig.clock.activeCount)
        var t=0L; var advanced=0L; val values=mutableListOf<Float>()
        repeat(120) { i ->
            t+=8_333_333L; if(rig.clock.activeCount>0) advanced++; rig.deliver(t); if(i%3==0) rig.deliver(t); if(i%5==0) rig.deliver(t-4_000_000L)
            assertEquals(advanced,rig.clock.presentedStamp.sequence); values+=m.value()
        }
        assertTrue("the motion ran for several frames",advanced>3); assertTrue("motion reached its target",m.settled); assertEquals(1f,m.value(),0f); assertEquals(0,rig.clock.activeCount)
        assertTrue("value never leaves the path",values.zipWithNext().all { (a,b) -> b>=a })
        m.dispose(); rig.close()
    }
}
