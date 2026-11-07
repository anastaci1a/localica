package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import org.junit.Assert.*
import org.junit.Test

/** D30: one shared phase and physical rectangle, including U02 Rebase residuals and final retirement. */
class SurfaceFollowTest {
    private val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,4000f,1f/30)) as SpecResult.Accepted).spec
    private val start=Rect(0f,0f,40f,40f)
    private val end=Rect(10f,20f,300f,600f)
    private class Fixture(val h: EngineHarness) {
        val plane=ShadowPlane(h.clock,HostKey.next(Any()))
        val expansion=ExpandCollapse(h.clock,h.clock.spec.expansion,false)
        val rect=MotionRect(h.clock,Rect(0f,0f,40f,40f),EdgeRanges(4000f,4000f,4000f,4000f))
        val node=SurfaceNode(h.clock,"face",PersistentShape(h.clock,ShapeTarget(emptyList(),JoinPolicy.Union)),SurfaceTarget(Stratum.Modal,0f,ShadowRole.Glass),plane)
        val curve=Curve.window(0f,1f)
        init { expansion.setBasis(ExpansionBasis(Rect(0f,0f,40f,40f),Rect(10f,20f,300f,600f),1f)); expansion.bind(rect); node.follow(expansion,curve,rect) }
    }

    @Test fun followsAnalyticPhaseAndTheSameRebasedPhysicalEdgesWithoutAdditionalActiveMotion() = EngineHarness(spec=spec).use { h ->
        val f=Fixture(h)
        f.expansion.target(true)
        assertEquals("Only phase and four bound edges run",5,h.clock.activeCount)
        assertSame(f.rect,f.node.transform)
        repeat(6) { i ->
            h.advance(.02)
            val stamp=h.clock.stampNow; val p=f.expansion.phase.sampleAt(stamp); val actual=f.node.opacity.sampleAt(stamp)
            assertEquals(f.curve.at(p.value),actual.value,0f)
            assertEquals(f.curve.slope(p.value)*p.velocity,actual.velocity,0f)
            if(i==2) {
                val edges=f.rect.channels.map { it.sampleAt(stamp) }
                assertEquals(BasisChange.Rebase,f.expansion.setBasis(ExpansionBasis(start,end.copy(right=450f),1f)))
                f.rect.channels.forEachIndexed { n,m -> assertEquals(edges[n].value,m.valueAt(stamp),1e-4f); assertEquals(edges[n].velocity,m.velocityAt(stamp),1e-3f) }
                assertSame("Rebase residuals stay in the borrowed rectangle",f.rect,f.node.transform)
            }
        }
        val before=f.node.opacity.sampleAt(h.clock.stampNow)
        f.expansion.target(false)
        val after=f.node.opacity.sampleAt(h.clock.stampNow)
        assertEquals(before.value,after.value,1e-4f); assertEquals(before.velocity,after.velocity,1e-3f)
        assertThrows(IllegalStateException::class.java) { f.node.request(SurfaceTarget(Stratum.Modal,1f,ShadowRole.Glass),motionProfileId(MotionProfile.Control)) }
        h.advance(2.0); assertEquals(0,h.clock.activeCount)
        f.node.dispose(); f.expansion.dispose()
    }

    @Test fun dismissalRevokesNowRetiresOutsideDrawAndReopensTheSameNode() = EngineHarness(spec=spec).use { h ->
        val f=Fixture(h); f.expansion.target(true); h.advance(.04)
        f.node.dismiss(); assertTrue("Logical dismissal retains even before the owner targets closed",f.node.retained)
        f.expansion.target(false)
        h.advance(.02); assertTrue(f.node.retained)
        val observer=SnapshotStateObserver { it() }; var invalidated=false
        observer.start(); observer.observeReads(Any(),{ _: Any -> invalidated=true }) { assertTrue(f.node.retained) }
        h.advance(2.0); Snapshot.sendApplyNotifications()
        assertFalse(f.node.retained); assertTrue(f.plane.casters.isEmpty()); assertTrue("Retirement invalidates its composing host",invalidated)
        observer.stop(); observer.clear()
        f.expansion.target(true); assertEquals(listOf(f.node),f.plane.casters); assertFalse(f.node.retained)
        h.advance(.03); f.node.dismiss(); f.expansion.target(false); h.advance(.01)
        val before=f.node.opacity.sampleAt(h.clock.stampNow); f.expansion.target(true)
        assertEquals(before.value,f.node.opacity.valueAt(h.clock.stampNow),1e-4f)
        assertEquals(before.velocity,f.node.opacity.velocityAt(h.clock.stampNow),1e-3f)
        assertEquals(listOf(f.node),f.plane.casters); assertFalse(f.node.retained)
        f.node.dismiss(); h.advance(2.0); assertTrue("An opening rest callback cannot revoke logical dismissal",f.node.retained)
        f.expansion.target(false); h.advance(2.0); assertFalse(f.node.retained)
        f.node.dispose(); f.expansion.dispose(); assertEquals(0,h.clock.activeCount)
    }

    @Test fun reducedRetirementIsSynchronousAndDisposalDoesNotOwnTheBorrowedRectangle() = EngineHarness(MotionAccess.Reduced,spec).use { h ->
        val f=Fixture(h); f.expansion.target(true); assertEquals(1f,f.node.opacity.valueAt(h.clock.stampNow),0f)
        f.node.dismiss(); f.expansion.target(false)
        assertFalse(f.node.retained); assertTrue(f.plane.casters.isEmpty()); assertEquals(0,h.clock.activeCount)
        f.node.dispose(); assertTrue(f.expansion.lifecycle.isEmpty())
        f.expansion.target(true); assertEquals(end,f.rect.valueAt(h.clock.stampNow))
        f.expansion.dispose()
    }

    @Test fun completionMayRemoveAndStartOtherActiveOwnersAtTheSameFrame() = EngineHarness(spec=spec).use { h ->
        val profile=motionProfileId(MotionProfile.Control)
        val first=MotionFloat(h.clock,0f,1f); val removed=MotionFloat(h.clock,0f,1f); val started=MotionFloat(h.clock,0f,1f)
        first.onRest={ removed.dispose(); started.retarget(1f,profile) }
        first.retarget(1f,profile); removed.retarget(1f,profile)
        h.advance(2.0)
        assertFalse(started.settled); assertEquals(1,h.clock.activeCount)
        h.advance(2.0); assertTrue(started.settled); assertEquals(0,h.clock.activeCount)
        first.dispose(); started.dispose()
    }

    @Test fun rejectAnUnboundOrForeignRectangleBeforeChangingTheNode() = EngineHarness(spec=spec).use { h ->
        val f=Fixture(h); val wrong=MotionRect(h.clock,start,EdgeRanges(4000f,4000f,4000f,4000f))
        assertThrows(IllegalArgumentException::class.java) { f.node.follow(f.expansion,f.curve,wrong) }
        assertSame(f.rect,f.node.transform); assertEquals(listOf(f.node),f.plane.casters)
        val standalone=SurfaceNode(h.clock,"standalone",PersistentShape(h.clock,ShapeTarget(emptyList(),JoinPolicy.Union)),SurfaceTarget(Stratum.Modal,0f,ShadowRole.Glass),f.plane)
        standalone.request(SurfaceTarget(Stratum.Modal,1f,ShadowRole.Glass),motionProfileId(MotionProfile.Control))
        assertThrows(IllegalStateException::class.java) { standalone.follow(f.expansion,f.curve,f.rect) }
        assertTrue(standalone.opacity is MotionFloat)
        standalone.dispose(); wrong.dispose(); f.node.dispose(); f.expansion.dispose()
    }
}
