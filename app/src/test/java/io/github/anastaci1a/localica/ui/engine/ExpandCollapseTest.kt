package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class ExpandCollapseTest {
    private fun basis(a: Float=100f,b: Float=200f)=ExpansionBasis(Rect(0f,0f,a,100f),Rect(0f,0f,b,100f),1f)
    @Test fun restingSheetReanchorsEveryEdgeWithoutStartingAPhaseSegment() {
        for (access in MotionAccess.entries) for (expanded in listOf(false, true)) {
            EngineHarness(access).use { h ->
                val old = basis()
                val c = ExpandCollapse(h.clock, StockUiSpec.expansion, expanded)
                c.setBasis(old)
                val rect = MotionRect(h.clock, if (expanded) old.endBounds else old.startBounds,
                    EdgeRanges(1000f, 1000f, 1000f, 1000f))
                c.bind(rect)
                for (step in 1..20) {
                    val next = ExpansionBasis(Rect(step.toFloat(), 2f, 100f + step, 90f),
                        Rect(3f, 4f, 200f + step, 120f), 1f)
                    c.setBasis(next, reanchorAtRest = true)
                    assertTrue("A basis replacement must keep the phase settled: $access", c.phase.settled)
                    assertEquals(if (expanded) 1f else 0f, c.phase.value(), 0f)
                    assertEquals(if (expanded) next.endBounds else next.startBounds, rect.valueAt(h.clock.stampNow))
                    assertTrue(rect.channels.all { it.settled && it.rawVelocity(h.clock.stampNow) == 0.0 })
                    assertEquals(0, h.clock.activeCount)
                    assertNull(c.pendingBasis)
                    h.advance(.016)
                }
                c.dispose()
            }
        }
    }

    @Test fun sheetReanchorPolicyPreservesInFlightContinuity() {
        for (access in listOf(MotionAccess.Standard, MotionAccess.Faster)) EngineHarness(access).use { h ->
            val old = basis()
            val c = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            c.setBasis(old)
            val rect = MotionRect(h.clock, old.startBounds, EdgeRanges(1000f, 1000f, 1000f, 1000f))
            c.bind(rect)
            c.target(true)
            h.advance(.04)
            val stamp = h.clock.stampNow
            val phase = c.phase.sampleAt(stamp)
            val edges = rect.channels.map { it.sampleAt(stamp) }
            val next = ExpansionBasis(Rect(10f, 20f, 120f, 110f), Rect(0f, 0f, 300f, 200f), 1f)
            c.setBasis(next, reanchorAtRest = true)
            assertFalse(c.phase.settled)
            assertEquals(phase, c.phase.sampleAt(stamp))
            assertEquals(edges, rect.channels.map { it.sampleAt(stamp) })
            h.advance(1.0)
            assertTrue(c.phase.settled)
            assertEquals(next.endBounds, rect.valueAt(h.clock.stampNow))
            c.dispose()
        }
    }

    @Test fun sheetReanchorPolicyDefersBasisWhileInContact() {
        EngineHarness().use { h ->
            val old = basis()
            val c = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            c.setBasis(old)
            val rect = MotionRect(h.clock, old.startBounds, EdgeRanges(1000f, 1000f, 1000f, 1000f))
            c.bind(rect)
            c.contactBegin(h.clock.stampNow)
            val next = basis(20f, 300f)
            c.setBasis(next, reanchorAtRest = true)
            assertEquals(next, c.pendingBasis)
            assertEquals(old.startBounds, rect.valueAt(h.clock.stampNow))
            h.now += 10_000_000
            c.contactMove(50f, h.clock.stampNow)
            assertEquals(150f, rect.valueAt(h.clock.stampNow).right, 0f)
            c.contactRelease(0f, h.clock.stampNow)
            h.advance(1.0)
            assertEquals(next.endBounds, rect.valueAt(h.clock.stampNow))
            c.dispose()
        }
    }

    @Test fun sheetReanchorPolicyCannotBypassAPendingNativeGate() {
        EngineHarness().use { h ->
            val old = basis()
            val c = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            c.setBasis(old)
            val rect = MotionRect(h.clock, old.startBounds, EdgeRanges(1000f, 1000f, 1000f, 1000f))
            c.bind(rect)
            c.nativeGate(.25f)
            c.target(true)
            h.advance(1.0)
            assertTrue(c.awaitingNativeGate)
            val generation = c.nativeGeneration
            val edges = rect.channels.map { it.sampleAt(h.clock.stampNow) }
            val next = basis(20f, 300f)
            c.setBasis(next, reanchorAtRest = true)
            assertEquals(.25f, c.phase.valueAt(h.clock.stampNow), 0f)
            assertEquals(edges, rect.channels.map { it.sampleAt(h.clock.stampNow) })
            assertEquals(generation, c.nativeGeneration)
            h.advance(1.0)
            assertTrue(c.awaitingNativeGate)
            assertTrue(c.acknowledgeNativeGate(generation))
            h.advance(1.0)
            assertEquals(next.endBounds, rect.valueAt(h.clock.stampNow))
            c.dispose()
        }
    }

    @Test fun defaultBasisPolicyRetainsPresentedGeometryAtRest() {
        EngineHarness().use { h ->
            val old = basis()
            val c = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            c.setBasis(old)
            val rect = MotionRect(h.clock, old.startBounds, EdgeRanges(1000f, 1000f, 1000f, 1000f))
            c.bind(rect)
            c.setBasis(basis(20f, 300f))
            assertFalse(c.phase.settled)
            assertEquals(old.startBounds, rect.valueAt(h.clock.stampNow))
            h.advance(1.0)
            c.dispose()
        }
    }
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
    @Test fun durationTravelTimesThePhaseOnlyAndLocalSpeedReplacesTheRootFactorOnce() {
        val domain=(uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(),null,ExpansionGeometryDomain(1000f,3000f,1f/60)) as SpecResult.Accepted).spec
        fun phases(access: MotionAccess,speed: Float?,basis: ExpansionBasis,bind: Boolean=false)=EngineHarness(access,domain).use { h ->
            val c=ExpandCollapse(h.clock,domain.expansion,false); c.setBasis(basis)
            val rect=if(bind) MotionRect(h.clock,basis.startBounds,EdgeRanges(3000f,3000f,3000f,3000f)).also { c.bind(it) } else null
            speed?.let(c::updateSpeed); c.target(true)
            val samples=List(40) { h.advance(1.0/120); c.phase.valueAt(h.clock.stampNow) to rect?.valueAt(h.clock.stampNow) }
            h.advance(2.0); assertTrue(c.phase.settled); val end=rect?.valueAt(h.clock.stampNow); c.dispose(); assertEquals(0,h.clock.activeCount); samples to end
        }
        val envelope=Rect(0f,0f,1000f,1000f); val parallax=ExpansionBasis(envelope.translate(28f,0f),envelope,1f,1000f)
        assertEquals(28f,parallax.edgeTravelPx,0f)
        // A 28 px slide timed by its 1000 px presented extent follows the same phase as a real 1000 px edge.
        val real=phases(MotionAccess.Standard,4f/3f,ExpansionBasis(Rect(0f,0f,1000f,0f),Rect(0f,0f,1000f,1000f),1f),bind=true)
        val (slide,end)=phases(MotionAccess.Standard,4f/3f,parallax,bind=true)
        assertEquals(real.first.map { it.first },slide.map { it.first }); assertEquals(envelope,end)
        slide.forEach { (p,r) -> assertTrue(r!!.left in 0f..28f && r.width==1000f && p in 0f..1f) }
        // Local speed is the whole time scale: the Faster root adds nothing, and 1.5 equals the old Faster root.
        assertEquals(phases(MotionAccess.Standard,1.5f,parallax).first,phases(MotionAccess.Faster,1.5f,parallax).first)
        assertEquals(phases(MotionAccess.Faster,null,parallax).first,phases(MotionAccess.Standard,1.5f,parallax).first)
        assertNotEquals(phases(MotionAccess.Standard,2f,parallax).first,phases(MotionAccess.Standard,1.5f,parallax).first)
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
