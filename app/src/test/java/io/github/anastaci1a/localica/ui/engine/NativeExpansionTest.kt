package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** Readiness barriers and prepared-origin geometry use the same #608 phase and physical edge plan. */
class NativeExpansionTest {
    private val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,4000f,1f/30)) as SpecResult.Accepted).spec
    private val start=Rect(40f,50f,88f,98f)
    private val end=Rect(10f,20f,310f,620f)
    private fun owner(h: EngineHarness,curve: Curve?=null): Pair<ExpandCollapse,MotionRect> {
        val e=ExpandCollapse(h.clock,spec.expansion,false)
        e.nativeGate(.25f); e.setBasis(ExpansionBasis(start,end,1f))
        val r=MotionRect(h.clock,start,EdgeRanges(4000f,4000f,4000f,4000f))
        if(curve==null) e.bind(r) else e.bind(r,curve)
        return e to r
    }

    @Test fun readinessHasNoFrameDeadlineOrIdleLoopAndOnlyItsCurrentGenerationAdmits() = EngineHarness(spec=spec).use { h ->
        val (e,r)=owner(h,Curve.window(.25f,1f))
        e.target(true); assertTrue(e.expanded); assertFalse(e.awaitingNativeGate)
        h.advance(2.0)
        assertTrue(e.awaitingNativeGate); assertFalse(e.retirementReady); assertEquals(.25f,e.phase.valueAt(h.clock.stampNow),0f)
        assertEquals(start,r.valueAt(h.clock.stampNow)); assertEquals(0,h.clock.activeCount)
        val generation=e.nativeGeneration; val awaits=h.awaits
        repeat(20) { h.advance(.1) }
        assertTrue(e.awaitingNativeGate); assertEquals(0,h.clock.activeCount); assertEquals(awaits,h.awaits)
        assertFalse(e.acknowledgeNativeGate(generation-1)); assertTrue(e.awaitingNativeGate)
        assertTrue(e.acknowledgeNativeGate(generation)); assertFalse(e.awaitingNativeGate)
        assertEquals(.25f,e.phase.valueAt(h.clock.stampNow),0f); assertEquals(0f,e.phase.velocityAt(h.clock.stampNow),0f)
        h.advance(2.0); assertEquals(1f,e.phase.valueAt(h.clock.stampNow),0f); assertEquals(end,r.valueAt(h.clock.stampNow)); assertTrue(e.retirementReady)
        e.dispose()
    }

    @Test fun pendingReturnCannotBeStolenByReopenAndOldReceiptsCannotReleaseTheNextOpening() = EngineHarness(spec=spec).use { h ->
        val (e,_)=owner(h); e.target(true); h.advance(2.0); e.acknowledgeNativeGate(e.nativeGeneration); h.advance(2.0)
        e.target(false); h.advance(2.0); assertEquals(false,e.nativeOpening)
        val returning=e.nativeGeneration
        e.target(true); assertTrue(e.expanded); assertTrue(e.awaitingNativeGate); assertEquals(false,e.nativeOpening)
        assertTrue(e.acknowledgeNativeGate(returning))
        assertTrue(e.awaitingNativeGate); assertEquals(true,e.nativeOpening); assertTrue(e.nativeGeneration>returning)
        assertFalse(e.acknowledgeNativeGate(returning)); assertTrue(e.acknowledgeNativeGate(e.nativeGeneration))
        h.advance(2.0); assertEquals(1f,e.phase.valueAt(h.clock.stampNow),0f)
        e.dispose()
    }

    @Test fun canceledUnadmittedOpeningReturnsWithoutAcquiringNativeOwnership() = EngineHarness(spec=spec).use { h ->
        val (e,_)=owner(h); e.target(true); h.advance(2.0); assertTrue(e.awaitingNativeGate)
        e.target(false); assertTrue(e.awaitingNativeGate)
        e.acknowledgeNativeGate(e.nativeGeneration); h.advance(2.0)
        assertEquals(0f,e.phase.valueAt(h.clock.stampNow),0f); assertFalse(e.awaitingNativeGate); assertTrue(e.retirementReady)
        e.dispose()
    }

    @Test fun mappedPhysicalEdgesPreserveValueAndVelocityAtRebaseAndReversal() = EngineHarness(spec=spec).use { h ->
        val curve=Curve.window(.25f,1f); val (e,r)=owner(h,curve)
        e.target(true); h.advance(2.0); e.acknowledgeNativeGate(e.nativeGeneration)
        repeat(4) { h.advance(.025) }
        assertEquals(5,h.clock.activeCount)
        val stamp=h.clock.stampNow; val before=r.channels.map { it.sampleAt(stamp) }
        val p=e.phase.sampleAt(stamp)
        assertEquals(start.left+(end.left-start.left)*curve.at(p.value),r.left.valueAt(stamp),1e-3f)
        assertEquals((end.left-start.left)*curve.slope(p.value)*p.velocity,r.left.velocityAt(stamp),1e-3f)
        assertEquals(BasisChange.Rebase,e.setBasis(ExpansionBasis(start,end.copy(right=460f,bottom=500f),1f)))
        r.channels.forEachIndexed { i,m -> assertEquals(before[i].value,m.valueAt(stamp),1e-3f); assertEquals(before[i].velocity,m.velocityAt(stamp),1e-2f) }
        h.advance(.02); val reversing=r.channels.map { it.sampleAt(h.clock.stampNow) }
        e.target(false)
        r.channels.forEachIndexed { i,m -> assertEquals(reversing[i].value,m.valueAt(h.clock.stampNow),1e-3f); assertEquals(reversing[i].velocity,m.velocityAt(h.clock.stampNow),1e-2f) }
        h.advance(2.0); assertTrue(e.awaitingNativeGate); assertEquals(start,r.valueAt(h.clock.stampNow))
        e.acknowledgeNativeGate(e.nativeGeneration); h.advance(2.0); assertTrue(e.retirementReady)
        e.dispose()
    }

    @Test fun reducedStillWaitsForBothNativeReceiptsAndObservableRetirement() = EngineHarness(MotionAccess.Reduced,spec).use { h ->
        val (e,r)=owner(h,Curve.window(.25f,1f)); e.target(true)
        assertTrue(e.awaitingNativeGate); assertEquals(start,r.valueAt(h.clock.stampNow)); assertEquals(0,h.clock.activeCount)
        e.acknowledgeNativeGate(e.nativeGeneration); assertEquals(end,r.valueAt(h.clock.stampNow))
        e.target(false); assertTrue(e.awaitingNativeGate); assertFalse(e.retirementReady)
        val observer=SnapshotStateObserver { it() }; var invalidated=false
        observer.start(); observer.observeReads(Any(),{ _: Any -> invalidated=true }) { assertFalse(e.retirementReady) }
        e.acknowledgeNativeGate(e.nativeGeneration); Snapshot.sendApplyNotifications()
        assertTrue(e.retirementReady); assertTrue(invalidated); assertEquals(start,r.valueAt(h.clock.stampNow)); assertEquals(0,h.clock.activeCount)
        observer.stop(); observer.clear(); e.dispose()
    }
}
