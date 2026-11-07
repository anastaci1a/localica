package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class ExpandCollapseTest {
    private fun basis(a: Float=100f,b: Float=200f)=ExpansionBasis(Rect(0f,0f,a,100f),Rect(0f,0f,b,100f),1f)
    @Test fun fullHalfAndSmallTravelLawUsePhysicalEdgesAndReferenceTime() {
        val spec=StockUiSpec.expansion
        assertEquals(.426086832,expansionDuration(spec,1000.0,1000.0,0.0,0.0,1.0,1.0,1.0/120),1e-7)
        assertEquals(.14,expansionDuration(spec,0.0,1000.0,0.0,0.0,1.0,1.0,1.0/120),1e-8)
        for(hz in listOf(60,90,120)) assertEquals(2.0/hz,expansionDuration(spec,1000.0,1000.0,.5,1e30,1.0,1.0,1.0/hz),1e-9)
        val translated=ExpansionBasis(Rect(0f,0f,100f,100f),Rect(20f,0f,120f,100f),1f)
        assertEquals(20f,translated.edgeTravelPx,0f); assertEquals(0,translated.edgeIndex)
    }
    @Test fun contactFreezesBasisPendingIntentAndReleaseRestoresOneLogicalPolicy() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false); c.setBasis(basis())
            c.contactBegin(h.clock.stampNow); h.now+=10_000_000; c.contactMove(50f,h.clock.stampNow)
            c.target(true); assertFalse(c.expanded); assertTrue(c.pendingTarget!!)
            assertEquals(BasisChange.Rebase,c.setBasis(basis(10f,300f))); assertNotNull(c.pendingBasis)
            h.now+=10_000_000; val decision=c.contactRelease(0f,h.clock.stampNow)
            assertTrue(decision.expanded); assertFalse(c.inContact); assertNull(c.pendingTarget); assertNotNull(c.pendingBasis)
            h.advance(1.0); assertTrue(c.phase.settled)
            assertThrows(IllegalStateException::class.java) { c.dispose() }; assertEquals(0,h.clock.activeCount)
        }
    }
    @Test fun boundRebasePreservesPhysicalAndPrivatePhaseAndSharesFinish() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false); val old=basis(); c.setBasis(old)
            val rect=MotionRect(h.clock,old.startBounds,EdgeRanges(1000f,1000f,1000f,1000f)); c.bind(rect); c.target(true)
            h.advance(.1); val stamp=h.clock.stampNow; val p=c.phase.sampleAt(stamp); val edges=rect.channels.map { it.sampleAt(stamp) }
            val changed=ExpansionBasis(old.startBounds,Rect(0f,0f,200f,200f),1f)
            assertEquals(BasisChange.Rebase,c.setBasis(changed)); assertEquals(p,c.phase.sampleAt(stamp)); assertEquals(edges,rect.channels.map { it.sampleAt(stamp) })
            h.advance(1.0); assertTrue(c.phase.settled); assertTrue(rect.channels.all { it.settled }); assertEquals(changed.endBounds,rect.valueAt(h.clock.stampNow)); c.dispose(); assertEquals(0,h.clock.activeCount)
        }
    }
    @Test fun rescaleRequiresPhaseDerivativeAsWellAsEveryEdge() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false); c.setBasis(basis())
            c.contactBegin(h.clock.stampNow); h.now+=1_000_000_000; c.contactMove(50f,h.clock.stampNow)
            h.now+=1_000_000_000; c.contactMove(50f,h.clock.stampNow); c.contactRelease(100f,h.clock.stampNow)
            // p=.5,v=1; physical right150, but the old/next affine phase rates would be1/.5.
            assertEquals(BasisChange.Rebase,c.setBasis(basis(50f,250f))); assertNotNull(c.pendingBasis)
            assertThrows(IllegalStateException::class.java) { c.dispose() }
        }
    }
    @Test fun cancellationAndReducedExitClearContactWithoutLateInputResurrection() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false); c.setBasis(basis()); c.contactBegin(h.clock.stampNow)
            c.target(true); c.contactCancel(); assertTrue(c.expanded); c.contactCancel()
            c.contactBegin(h.clock.stampNow); c.target(false)
            h.clock.configure(EngineContext(1f/60,2000f,MotionAccess.Reduced),StockUiSpec)
            assertFalse(c.inContact); assertTrue(c.expanded); assertNull(c.pendingTarget); assertEquals(1f,c.phase.value(),0f)
            assertThrows(IllegalStateException::class.java) { c.contactRelease(1f,h.clock.stampNow) }; c.dispose()
        }
    }
}
