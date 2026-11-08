package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class BoundedTrajectoryTest {
    @Test fun genericBackEasingIsAllowedButAlphaRejectsWholeCoreAndOutwardStartup() {
        val back=EasingSpec(.25f,2f,.75f,2f)
        val free=Eased(0.0,0.0,1.0,back,.3)
        assertTrue(free.value(.15)>1)
        assertThrows(IllegalArgumentException::class.java) { Eased(0.0,0.0,1.0,back,.3,bounds=0.0..1.0) }
        val standard=EasingSpec(.2f,0f,.2f,1f)
        assertThrows(IllegalArgumentException::class.java) { Eased(1.0,1.0,0.0,standard,.3,bounds=0.0..1.0) }
        val bounded=Eased(.5,1000.0,1.0,standard,.3,bounds=0.0..1.0)
        for(i in 0..10000) assertTrue(bounded.value(.3*i/10000) in 0.0..1.0)
    }
    @Test fun epochInvalidatesAtIntentAndSettlementButNotEveryFrame() {
        EngineHarness().use { h ->
            val motion=MotionFloat(h.clock,0f,1f); var changed=0
            val observer=SnapshotStateObserver { it() }; observer.start()
            val notify: (String)->Unit={ changed++ }
            fun subscribe() { observer.observeReads("draw",notify) { motion.value() } }
            subscribe(); motion.retarget(1f,motionProfileId(MotionProfile.Control)); Snapshot.sendApplyNotifications(); assertEquals(1,changed)
            subscribe(); h.advance(.01); Snapshot.sendApplyNotifications(); assertEquals(2,changed)
            subscribe(); h.advance(.4); Snapshot.sendApplyNotifications(); assertEquals(3,changed)
            assertTrue(motion.settled); subscribe(); h.advance(.01); Snapshot.sendApplyNotifications(); assertEquals(3,changed)
            observer.stop(); observer.clear(); motion.dispose()
        }
    }
    @Test fun atomicBoundChannelsRequireOneSourcePlanAndFinishTogether() {
        EngineHarness().use { h ->
            val a=MotionFloat(h.clock,0f,1f); val b=MotionFloat(h.clock,0f,1f); val stamp=h.clock.stampNow
            val plan=EasedPlan(stamp,StockUiSpec.expansion.core,.4,1.0,1.0/120)
            val tightened=plan.copy(tauOut=.00001); assertEquals(.00001,tightened.segment(0.0,0.0,1.0).tauOut,0.0)
            val other=plan.copy()
            val x=a.prepareSegment(plan.segment(0.0,0.0,1.0),1f,stamp,plan)
            val bad=b.prepareSegment(other.segment(0.0,0.0,1.0),1f,stamp,other)
            assertThrows(IllegalArgumentException::class.java) { commitAtomic(listOf(a to x,b to bad)) }; assertEquals(0f,a.target,0f)
            val y=b.prepareSegment(plan.segment(0.0,0.0,1.0),1f,stamp,plan)
            commitAtomic(listOf(a to x,b to y)); h.advance(.399); assertFalse(a.settled); assertFalse(b.settled)
            h.advance(.002); assertTrue(a.settled); assertTrue(b.settled); a.dispose(); b.dispose()
        }
    }

}
