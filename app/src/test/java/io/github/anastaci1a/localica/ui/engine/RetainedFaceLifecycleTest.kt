package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class RetainedFaceLifecycleTest {
    private val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,4000f,1f/30)) as SpecResult.Accepted).spec
    private val start=Rect(0f,0f,80f,40f)
    private val end=Rect(0f,0f,240f,120f)
    private fun owner(h: EngineHarness,initial: Boolean=false): Pair<ExpandCollapse,MotionRect> {
        val e=ExpandCollapse(h.clock,spec.expansion,initial)
        e.setBasis(ExpansionBasis(start,end,1f))
        val r=MotionRect(h.clock,if(initial) end else start,EdgeRanges(4000f,4000f,4000f,4000f))
        e.bind(r)
        return e to r
    }
    private fun node(h: EngineHarness,e: ExpandCollapse,r: MotionRect,p: ShadowPlane): SurfaceNode {
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(start,CornerRadius.Zero)),JoinPolicy.Union))
        return SurfaceNode(h.clock,"compact",shape,SurfaceTarget(Stratum.Floating,1f,ShadowRole.None),p)
            .also { it.follow(e,Curve.linear(1f,0f),r) }
    }

    @Test fun inverseFaceRetiresAtZeroThenReopensOnTheSameNodeAndClock() = EngineHarness(spec=spec).use { h ->
        val plane=ShadowPlane(h.clock,HostKey.next(Any())); val (e,r)=owner(h)
        val n=node(h,e,r,plane)
        e.target(true); n.dismiss(); assertTrue(n.retained)
        h.advance(2.0)
        assertFalse("The compact face retires although the expansion is open",n.retained)
        assertTrue(plane.casters.isEmpty()); assertEquals(0,h.clock.activeCount)
        e.target(false)
        assertSame(n,plane.casters.single()); assertFalse(n.retained)
        h.advance(2.0)
        assertEquals(1f,n.opacity.valueAt(h.clock.stampNow),0f)
        n.dispose(); e.dispose()
    }

    @Test fun alreadyHiddenInverseFaceRetiresWithoutNeedingAnotherFrame() = EngineHarness(spec=spec).use { h ->
        val plane=ShadowPlane(h.clock,HostKey.next(Any())); val (e,r)=owner(h,true)
        val n=node(h,e,r,plane)
        n.dismiss()
        assertFalse(n.retained); assertTrue(plane.casters.isEmpty()); assertEquals(0,h.clock.activeCount)
        n.dispose(); e.dispose()
    }
}
