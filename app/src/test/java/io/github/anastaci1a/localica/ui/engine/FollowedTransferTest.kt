package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** D27/D30: native transfer borrows the popup phase, never starts a second ownership trajectory. */
class FollowedTransferTest {
    private val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,4000f,1f/30)) as SpecResult.Accepted).spec
    private val start=Rect(0f,0f,48f,48f)
    private val end=Rect(40f,50f,300f,500f)
    private val profile=motionProfileId(MotionProfile.Control)
    private fun plane(h: EngineHarness)=ShadowPlane(h.clock,HostKey.next(Any()))
    private fun motion(h: EngineHarness): Pair<ExpandCollapse,MotionRect> {
        val e=ExpandCollapse(h.clock,spec.expansion,false); e.nativeGate(0f); e.setBasis(ExpansionBasis(start,end,1f))
        val r=MotionRect(h.clock,start,EdgeRanges(4000f,4000f,4000f,4000f)); e.bind(r); return e to r
    }

    @Test fun transfersAndReturnsOnTheExactSharedPhaseAfterRealReceipts() = EngineHarness(spec=spec).use { h ->
        val a=plane(h); val b=plane(h); val (e,r)=motion(h)
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(start,CornerRadius(24f))),JoinPolicy.Union))
        val n=SurfaceNode(h.clock,"trigger",shape,SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),a)
        n.follow(e,Curve.constant(1f),r)
        e.target(true); assertTrue(e.awaitingNativeGate)
        n.transfer(b,profile); assertTrue(n.waiting); assertTrue(n.owns(a)); assertEquals(0,h.clock.activeCount)
        b.acknowledge(); assertFalse(n.waiting); assertTrue(n.owns(b)); assertFalse(n.owns(a))
        assertEquals("Readiness alone starts no ownership animation",0,h.clock.activeCount)
        e.acknowledgeNativeGate(e.nativeGeneration)
        repeat(6) {
            h.advance(.02)
            val stamp=h.clock.stampNow
            assertEquals(e.phase.valueAt(stamp),n.shadowOwnership.valueAt(stamp),0f)
            assertEquals(e.phase.velocityAt(stamp),n.shadowOwnership.velocityAt(stamp),0f)
            assertEquals(1f,n.shareAt(a,stamp)+n.shareAt(b,stamp),1e-6f)
            assertEquals(1f,a.contributionAt(n,stamp)+b.contributionAt(n,stamp),0f)
            assertTrue("Only phase and four physical edges run",h.clock.activeCount<=5)
        }
        h.advance(2.0); assertEquals(0,h.clock.activeCount)
        e.target(false); h.advance(2.0); assertTrue(e.awaitingNativeGate)
        n.transfer(a,profile)
        assertTrue("The returning field stays native until the source draws",n.owns(b))
        a.acknowledge(); assertTrue(n.owns(a)); assertFalse(n.owns(b)); assertTrue(b.casters.isEmpty())
        assertSame(n,a.casters.single()); assertSame(shape,n.shape)
        assertEquals(1f,n.shareAt(a,h.clock.stampNow),0f)
        e.acknowledgeNativeGate(e.nativeGeneration); assertTrue(e.retirementReady)
        n.dispose(); e.dispose()
    }

    @Test fun aNewPopupLifetimeRebindsOnlyAfterTheSameNodeReturnedAndClosed() = EngineHarness(spec=spec).use { h ->
        val a=plane(h); val b=plane(h); val (first,r)=motion(h); val (next,r2)=motion(h)
        val n=SurfaceNode(h.clock,"same-home",PersistentShape(h.clock,ShapeTarget(emptyList(),JoinPolicy.Union)),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),a)
        n.follow(first,Curve.constant(1f),r)
        first.target(true); n.transfer(b,profile); b.acknowledge(); first.acknowledgeNativeGate(first.nativeGeneration); h.advance(.03)
        assertThrows(IllegalArgumentException::class.java) { n.follow(next,Curve.constant(1f),r2) }
        assertSame(r,n.transform)
        first.target(false); h.advance(2.0); n.transfer(a,profile); a.acknowledge(); first.acknowledgeNativeGate(first.nativeGeneration)
        n.follow(next,Curve.constant(1f),r2)
        assertSame(r2,n.transform); assertEquals(1f,n.opacity.valueAt(h.clock.stampNow),0f)
        assertTrue("The old popup releases its lifetime listener",first.lifecycle.isEmpty())
        next.target(true); assertTrue(next.awaitingNativeGate)
        n.dispose(); first.dispose(); next.dispose()
    }

    @Test fun aReturnAbandonedAtItsFrameAllowanceHandsTheFieldBackToTheSourceBeforeTheNextLifetime() = EngineHarness(spec=spec).use { h ->
        val a=plane(h); val b=plane(h); val (first,r)=motion(h); val (next,r2)=motion(h)
        val n=SurfaceNode(h.clock,"abandoned-return",PersistentShape(h.clock,ShapeTarget(emptyList(),JoinPolicy.Union)),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),a)
        n.follow(first,Curve.constant(1f),r)
        first.target(true); n.transfer(b,profile); b.acknowledge(); first.acknowledgeNativeGate(first.nativeGeneration); h.advance(.03)
        first.target(false); h.advance(2.0); n.transfer(a,profile)
        assertTrue("The source never drew, so the window still owns the field",n.owns(b)); assertTrue(n.waiting)
        first.acknowledgeNativeGate(first.nativeGeneration); assertTrue(first.retirementReady)
        n.follow(next,Curve.constant(1f),r2)
        assertSame(r2,n.transform); assertTrue(n.owns(a)); assertFalse(n.owns(b)); assertFalse(n.waiting)
        assertSame(n,a.casters.single()); assertTrue(b.casters.isEmpty())
        assertTrue("The old popup releases its lifetime listener",first.lifecycle.isEmpty())
        next.target(true); assertTrue(next.awaitingNativeGate)
        n.transfer(b,profile); b.acknowledge(); assertTrue("The next lifetime transfers normally",n.owns(b))
        n.dispose(); first.dispose(); next.dispose()
    }
}
