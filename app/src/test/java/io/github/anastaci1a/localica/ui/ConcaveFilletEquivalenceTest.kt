package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * U03.01b (D14/D15): the Engine ConcaveFillet backend feeds the moved #556 solver exactly what the root
 * HoverPathCache feeds it, over the integrated AnalyticSelectionOutlineTest corpus. Native path parity is
 * ConcaveFilletPathParityTest.
 */
class ConcaveFilletEquivalenceTest {
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private val profile=motionProfileId(MotionProfile.Selection)

    /** HoverPathCache's route: canonical local regions from the first region, pair strengths captured once with the far-pair cutoff. */
    private fun legacy(regions: List<SelectionRegion>,gap: Float,strength: (Int,Int)->Float): Pair<Offset,List<OutlineFilletProof>> {
        val origin=regions.first().bounds.topLeft; val local=localLensRegions(regions,origin)
        return origin to AnalyticSelectionOutline().inspect(local.map { it.contour(gap) },gap) { a,b ->
            val lo=min(a,b); val hi=max(a,b); filletStrength(local[lo].bounds,local[hi].bounds,gap,strength(lo,hi)) }
    }
    private fun engine(bodies: List<RoundRect>,data: JoinData): Pair<Offset,List<OutlineFilletProof>> {
        val (origin,local,strength)=filletInputs(bodies,data); return origin to AnalyticSelectionOutline().inspect(local,data.gap,strength)
    }
    private fun regionsOf(bodies: List<RoundRect>)=bodies.map { SelectionRegion(it.boundingRect,listOf(it.topLeftCornerRadius,it.topRightCornerRadius,it.bottomRightCornerRadius,it.bottomLeftCornerRadius).minOf { r -> min(r.x,r.y) }) }
    private fun close(message: String,expected: List<OutlineFilletProof>,actual: List<OutlineFilletProof>,tolerance: Double) {
        assertEquals(message,expected.size,actual.size)
        for((e,a) in expected.zip(actual)) for((p,q) in listOf(e.center to a.center,e.a to a.a,e.b to a.b,e.tangentA to a.tangentA,e.tangentB to a.tangentB))
            assertTrue("$message $e $a",abs(p.x-q.x)<=tolerance && abs(p.y-q.y)<=tolerance && abs(e.radius-a.radius)<=tolerance)
    }
    /** The integrated corpus: the 12-item flight (and 11-item removal, reversed every seventh frame) at bridge strength 1-p. */
    private fun corpus(frame: Int,count: Int): List<RoundRect> { val p=frame/120f
        return List(12) { i -> val x=i%3*73f; val y=i/3*73f; val tx=x+(80f+min(i,2)*6f-x)*p; val ty=y+(70f+min(i,2)*6f-y)*p
            RoundRect(tx,ty,tx+74.25f,ty+74.25f,CornerRadius(12f)) }.filterIndexed { i,_ -> count==12 || i!=5 }.let { if(frame%7==0) it.reversed() else it } }
    private fun settled(h: EngineHarness,bodies: List<RoundRect>,data: JoinData,key: Any): ShapeFrame {
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),key); shape.joinPolicyData(data); shape.retarget(ShapeTarget(bodies,JoinPolicy.ConcaveFillet),profile); return shape.outline()
    }

    @Test fun engineFramesFeedTheSolverWhatHoverPathCacheFeedsIt() = EngineHarness(spec=geometry).use { h ->
        for(frame in 0..120) for(count in listOf(12,11)) {
            val bodies=corpus(frame,count); val p=frame/120f; val data=JoinData(16f,bodies.map { it.boundingRect }) { _,_ -> 1f-p }
            val shown=settled(h,bodies,data,frame to count)
            assertEquals(bodies.size,shown.bodies.size)
            // Exact: the frame's own bodies through both routes give bit-identical solver proofs and the same origin.
            val (o,expected)=legacy(regionsOf(shown.bodies),16f,data.strength); val (q,actual)=engine(shown.bodies,data)
            assertEquals("frame=$frame count=$count",o,q); assertEquals("frame=$frame count=$count",expected,actual)
            // Against the caller's own corpus geometry (chart round trip only): within 1e-4 px.
            close("frame=$frame count=$count",legacy(regionsOf(bodies),16f,data.strength).second,actual,1e-4)
        }
    }

    @Test fun midMorphFramesAndRetainedGeometryStayEquivalent() = EngineHarness(spec=geometry).use { h ->
        val start=corpus(0,12); val data=JoinData(16f,start.map { it.boundingRect }) { a,b -> if((a+b)%3==0) .4f else 1f }
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"group"); shape.joinPolicyData(data); shape.retarget(ShapeTarget(start,JoinPolicy.ConcaveFillet),profile); shape.outline()
        for(step in 1..40) {
            shape.retarget(ShapeTarget(corpus(step*3,12),JoinPolicy.ConcaveFillet),profile)
            h.advance(.016); val moving=shape.outline()
            val (o,expected)=legacy(regionsOf(moving.bodies),16f,data.strength); val (q,actual)=engine(moving.bodies,data)
            assertEquals("step=$step",o,q); assertEquals("step=$step",expected,actual)
        }
    }

    @Test fun randomGroupsAndGapsMatchTheLegacyRoute() = EngineHarness(spec=geometry).use { h ->
        val r=Random(600301)
        repeat(2000) { n ->
            val gap=listOf(16f,28f,42f)[n%3]; val size=1+r.nextInt(6)
            val bodies=List(size) { val l=r.nextFloat()*300; val t=r.nextFloat()*300; val w=8+r.nextFloat()*120; val ht=8+r.nextFloat()*120
                RoundRect(l,t,l+w,t+ht,CornerRadius(r.nextFloat()*min(w,ht)/2)) }
            val strengths=FloatArray(36) { r.nextFloat() }; val data=JoinData(gap,bodies.map { it.boundingRect }) { a,b -> strengths[a*6+b] }
            val shown=settled(h,bodies,data,n)
            val (o,expected)=legacy(regionsOf(shown.bodies),gap,data.strength); val (q,actual)=engine(shown.bodies,data)
            assertEquals("n=$n",o,q); assertEquals("n=$n",expected,actual)
        }
    }

    @Test fun zeroAreaBodiesLeaveTheSolverAndKeepTheirNeighboursStrengths() {
        val a=RoundRect(0f,0f,72f,72f,CornerRadius(12f)); val gone=RoundRect(80f,0f,80f,0f,CornerRadius.Zero); val b=RoundRect(76f,0f,148f,72f,CornerRadius(12f))
        val data=JoinData(16f,emptyList()) { i,j -> if(i==0 && j==2) .75f else 0f }
        val (origin,local,strength)=filletInputs(listOf(a,gone,b),data)
        assertEquals(Offset.Zero,origin); assertEquals(2,local.size); assertEquals(.75f,strength(0,1)); assertEquals(.75f,strength(1,0))
        assertEquals(engine(listOf(a,gone,b),data).second,legacy(listOf(SelectionRegion(a.boundingRect,12f),SelectionRegion(b.boundingRect,12f)),16f) { _,_ -> .75f }.second)
    }
}

/** D14: ConcaveFillet admission rejects before any channel or registration changes. */
class ConcaveFilletAdmissionTest {
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private val profile=motionProfileId(MotionProfile.Selection)
    private val body=RoundRect(0f,0f,100f,40f,CornerRadius(12f))
    private val data=JoinData(16f,listOf(body.boundingRect)) { _,_ -> 1f }

    @Test fun mixedCornerBodiesRejectBeforeMutation() = EngineHarness(spec=geometry).use { h ->
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"group"); shape.joinPolicyData(data)
        shape.retarget(ShapeTarget(listOf(body),JoinPolicy.ConcaveFillet),profile); val before=shape.outline(); val ids=shape.slotIds()
        for(mixed in listOf(RoundRect(0f,0f,100f,40f,CornerRadius(12f),CornerRadius(12f),CornerRadius(8f),CornerRadius(12f)),
                RoundRect(0f,0f,100f,40f,CornerRadius(12f,10f)))) {
            assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(body,mixed),JoinPolicy.ConcaveFillet),profile) }
            assertThrows(IllegalArgumentException::class.java) { shape.drive(ShapeTarget(listOf(mixed),JoinPolicy.ConcaveFillet),h.clock.stampNow) }
        }
        assertSame(before,shape.outline()); assertEquals(ids,shape.slotIds()); assertEquals(0,h.clock.activeCount)
        // Union keeps admitting asymmetric corners.
        shape.retarget(ShapeTarget(listOf(RoundRect(0f,0f,100f,40f,CornerRadius(12f,10f))),JoinPolicy.Union),profile)
    }

    @Test fun concaveFilletBodiesNeedJoinDataAndAValidGap() = EngineHarness(spec=geometry).use { h ->
        assertThrows(IllegalArgumentException::class.java) { PersistentShape(h.clock,ShapeTarget(listOf(body)),"no data") }
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"late")
        assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(body),JoinPolicy.ConcaveFillet),profile) }
        for(gap in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { shape.joinPolicyData(JoinData(gap,emptyList()) { _,_ -> 1f }) }
        assertTrue(shape.outline().bodies.isEmpty()); assertEquals(0,h.clock.activeCount)
        // Read once above, so the body is a birth morph from the empty frame; let it settle.
        shape.joinPolicyData(data); shape.retarget(ShapeTarget(listOf(body),JoinPolicy.ConcaveFillet),profile); h.advance(1.0)
        assertEquals(listOf(body),shape.outline().bodies.map { RoundRect(it.left,it.top,it.right,it.bottom,CornerRadius(12f)) })
    }

    @Test fun joinDataChangePublishesANewFrame() = EngineHarness(spec=geometry).use { h ->
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"epoch"); shape.joinPolicyData(data)
        shape.retarget(ShapeTarget(listOf(body),JoinPolicy.ConcaveFillet),profile); val before=shape.outline()
        shape.joinPolicyData(JoinData(28f,listOf(body.boundingRect)) { _,_ -> .5f })
        assertNotSame(before,shape.outline()); assertEquals(before.bodies,shape.outline().bodies)
    }
}
