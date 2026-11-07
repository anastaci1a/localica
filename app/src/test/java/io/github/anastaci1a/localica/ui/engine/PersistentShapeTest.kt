package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/** U03.01a foundation: one persistent owner, immutable frames, lazy reads, body correspondence and continuity. */
class PersistentShapeTest {
    private val profile=motionProfileId(MotionProfile.Selection)
    // D12: px channel ranges come from the admitted geometry domain.
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private fun pill(l: Float,t: Float,w: Float,h: Float)=RoundRect(l,t,l+w,t+h,CornerRadius(h/2))
    private fun channels(f: ShapeFrame)=f.bodies.flatMap { b -> listOf(b.left,b.top,b.right,b.bottom,b.topLeftCornerRadius.x,b.topLeftCornerRadius.y,b.topRightCornerRadius.x,b.topRightCornerRadius.y,b.bottomRightCornerRadius.x,b.bottomRightCornerRadius.y,b.bottomLeftCornerRadius.x,b.bottomLeftCornerRadius.y) }
    private fun area(b: RoundRect)=b.width*b.height
    private fun randomBody(r: Random): RoundRect { val l=r.nextFloat()*400-200; val t=r.nextFloat()*400-200; val w=r.nextFloat()*200; val h=r.nextFloat()*200; return RoundRect(l,t,l+w,t+h,CornerRadius(r.nextFloat()*w/2,r.nextFloat()*h/2),CornerRadius(r.nextFloat()*w/2,r.nextFloat()*h/2),CornerRadius(r.nextFloat()*w/2,r.nextFloat()*h/2),CornerRadius(r.nextFloat()*w/2,r.nextFloat()*h/2)) }

    @Test fun targetChangeKeepsIdentityAndOldFrameImmutable() = EngineHarness(spec=geometry).use { h ->
        val a=ShapeTarget(listOf(pill(0f,0f,200f,64f)),JoinPolicy.Union); val b=ShapeTarget(listOf(pill(0f,0f,96f,64f),pill(104f,0f,96f,64f)),JoinPolicy.Union,GlassStrain(.1f,-.05f))
        val shape=PersistentShape(h.clock,a,"pill"); assertEquals("pill",shape.key)
        val first=shape.outline(); val firstBodies=first.bodies.toList(); val identity=System.identityHashCode(shape)
        shape.retarget(b,profile); h.advance(.05); val middle=shape.outline(); h.advance(1.0); val last=shape.outline()
        assertEquals(identity,System.identityHashCode(shape)); assertEquals("pill",shape.key)
        assertEquals(firstBodies,first.bodies); assertNotSame(first,middle); assertEquals(b.bodies,last.bodies); assertEquals(b.strain,last.strain)
        assertThrows(UnsupportedOperationException::class.java) { (first.bodies as MutableList<RoundRect>).add(pill(0f,0f,1f,1f)) }
        // The caller's list is copied at intent; later caller mutation never reaches a published frame.
        val mutable=mutableListOf(pill(0f,0f,10f,10f)); shape.retarget(ShapeTarget(mutable,JoinPolicy.Union),profile); mutable[0]=pill(500f,500f,10f,10f); h.advance(1.0)
        assertEquals(listOf(pill(0f,0f,10f,10f)),shape.outline().bodies)
    }

    @Test fun lazyOutlineEquivalenceAndSettledReadsAllocateNothing() = EngineHarness(spec=geometry).use { h ->
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(pill(0f,0f,100f,40f)),JoinPolicy.Union),"lazy")
        val settled=shape.outline(); assertSame(settled,shape.outline()); assertSame(settled,shape.outlineAt(h.clock.stamp))
        var stampReads=0; val stampState=h.clock.stamp
        val observer=Snapshot.registerApplyObserver { _,_ -> }
        Snapshot.observe(readObserver={ if(it.toString().contains("FrameStamp")) stampReads++ }) { shape.outline() }
        assertEquals("A settled outline must not observe the clock stamp",0,stampReads); observer.dispose(); assertSame(stampState,h.clock.stamp)
        shape.retarget(ShapeTarget(listOf(pill(50f,20f,300f,60f)),JoinPolicy.Union),profile)
        repeat(40) { h.advance(1.0/120); val o=shape.outline(); val e=shape.outlineAt(h.clock.stamp); assertEquals(e.bodies,o.bodies); assertEquals(e.bounds,o.bounds); assertEquals(e.cornerRadius,o.cornerRadius,0f); assertSame(o,shape.outline()) }
        h.advance(1.0); val rest=shape.outline(); assertSame(rest,shape.outline()); assertSame(rest,shape.outlineAt(h.clock.stamp))
    }

    @Test fun invalidTargetsRejectAtomicallyAndEmptyTargetsContractToZeroCoverage() = EngineHarness(spec=geometry).use { h ->
        val start=ShapeTarget(listOf(pill(0f,0f,100f,40f),pill(120f,0f,100f,40f)),JoinPolicy.Union)
        val shape=PersistentShape(h.clock,start,"atomic"); val before=shape.outline()
        for(bad in listOf(RoundRect(0f,0f,Float.NaN,10f),RoundRect(10f,0f,0f,10f),RoundRect(0f,10f,10f,0f),RoundRect(0f,0f,10f,10f,CornerRadius(-1f)),RoundRect(0f,0f,Float.POSITIVE_INFINITY,10f))) {
            assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(pill(0f,0f,10f,10f),bad),JoinPolicy.Union),profile) }
            assertThrows(IllegalArgumentException::class.java) { shape.drive(ShapeTarget(listOf(bad),JoinPolicy.Union),h.clock.stampNow) }
            assertEquals(before.bodies,shape.outline().bodies); assertEquals(0,h.clock.activeCount)
        }
        // Overlapping radii canonicalize once to legal geometry (Skia's uniform scale), not per frame.
        shape.retarget(ShapeTarget(listOf(RoundRect(0f,0f,100f,40f,CornerRadius(80f))),JoinPolicy.Union),profile); h.advance(1.0)
        val legal=shape.outline().bodies.single(); assertEquals(20f,legal.topLeftCornerRadius.y,1e-4f); assertEquals(20f,legal.topLeftCornerRadius.x,1e-4f)
        shape.retarget(ShapeTarget(emptyList(),JoinPolicy.Union),profile); h.advance(1.0/120); val shrinking=shape.outline()
        assertTrue(shrinking.bodies.isNotEmpty() && shrinking.bodies.all { area(it)>0f })
        h.advance(2.0); val empty=shape.outline(); assertTrue(empty.bodies.isEmpty()); assertEquals(Rect.Zero,empty.bounds); assertEquals(0f,empty.cornerRadius,0f); assertEquals(GlassStrain.None,empty.strain)
        val fresh=PersistentShape(h.clock,ShapeTarget(emptyList()),"empty").outline(); assertTrue(fresh.bodies.isEmpty()); assertEquals(Rect.Zero,fresh.bounds)
    }

    @Test fun firstRetargetBeforePresentationInitializesAndLaterAppearanceIsABirthMorph() = EngineHarness(spec=geometry).use { h ->
        val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"birth"); val body=pill(10f,10f,80f,40f)
        shape.retarget(ShapeTarget(listOf(body),JoinPolicy.Union),profile); assertEquals(listOf(body),shape.outline().bodies); assertEquals(0,h.clock.activeCount)
        val second=pill(100f,10f,80f,40f); shape.retarget(ShapeTarget(listOf(body,second),JoinPolicy.Union),profile)
        val s=h.clock.stampNow; val born=shape.outlineAt(s).bodies[1]; assertEquals(0f,area(born),0f)
        // Nearest presented boundary: the right edge of the existing body at the new body's centre height.
        assertEquals(90f,born.left,1e-4f); assertEquals(30f,born.top,1e-4f)
        val dt=1e-4; h.now+=(dt*1e9).toLong(); val early=area(shape.outlineAt(h.clock.stampNow).bodies[1])
        assertTrue("Area derivative is zero at creation: $early",early/dt<1.0)
        h.advance(1.0); assertEquals(listOf(body,second),shape.outline().bodies)
    }

    @Test fun deathContractsToNeighbourAndReversalReusesRetainedChannels() = EngineHarness(spec=geometry).use { h ->
        val a=pill(0f,0f,90f,40f); val b=pill(100f,0f,90f,40f); val c=pill(200f,0f,90f,40f)
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(a,b,c),JoinPolicy.Union),"death"); shape.outline()
        val ids=shape.slotIds()
        shape.retarget(ShapeTarget(listOf(a,c),JoinPolicy.Union),profile); h.advance(.05)
        val dying=shape.outline().bodies; assertEquals(3,dying.size); assertTrue(area(dying[1]) in 0f..area(b))
        assertEquals(ids,shape.slotIds())
        shape.retarget(ShapeTarget(listOf(a,b,c),JoinPolicy.Union),profile); assertEquals("Reappearance reuses the retiring slot",ids,shape.slotIds())
        shape.retarget(ShapeTarget(listOf(a,c),JoinPolicy.Union),profile); h.advance(.1)
        var previous=area(shape.outline().bodies[1]); var t=0.0; var finalRate=Double.NaN
        while(t<1.0) { h.advance(1.0/240); t+=1.0/240; val bodies=shape.outline().bodies; if(bodies.size<3) break; val now=area(bodies[1]); assertTrue("monotone contraction",now<=previous+1e-3f); finalRate=(previous-now)*240.0; previous=now }
        assertEquals(0.0,previous.toDouble(),1e-3); assertTrue("zero area derivative at retirement: $finalRate",finalRate<5.0)
        h.advance(1.0); shape.retarget(ShapeTarget(listOf(a,c),JoinPolicy.Union),profile); assertEquals(listOf(a,c),shape.outline().bodies); assertEquals(listOf(ids[0],ids[2]),shape.slotIds())
    }

    @Test fun correspondenceIsDeterministicForInsertionAndRemoval() = EngineHarness(spec=geometry).use { h ->
        val bodies=List(4) { pill(it*100f,0f,90f,40f) }
        val shape=PersistentShape(h.clock,ShapeTarget(bodies,JoinPolicy.Union),"order"); shape.outline(); val ids=shape.slotIds()
        shape.retarget(ShapeTarget(bodies.take(2)+pill(250f,0f,40f,40f)+bodies.drop(2),JoinPolicy.Union),profile)
        val after=shape.slotIds(); assertEquals(5,after.size); assertEquals(ids.take(2),after.take(2)); assertEquals(ids.drop(2),after.drop(3)); assertFalse(after[2] in ids)
        val twin=PersistentShape(h.clock,ShapeTarget(bodies,JoinPolicy.Union),"twin"); twin.outline(); twin.retarget(ShapeTarget(bodies.take(2)+pill(250f,0f,40f,40f)+bodies.drop(2),JoinPolicy.Union),profile)
        assertEquals(shape.outline().bodies,twin.outline().bodies)
        shape.retarget(ShapeTarget(listOf(bodies[0],bodies[2],bodies[3]),JoinPolicy.Union),profile); val removed=shape.slotIds()
        assertEquals(after,removed); h.advance(1.0); shape.retarget(ShapeTarget(listOf(bodies[0],bodies[2],bodies[3]),JoinPolicy.Union),profile)
        assertEquals(listOf(ids[0],ids[2],ids[3]),shape.slotIds())
    }

    @Test fun tenThousandEdgeAndRadiusEventsJoinContinuously() = EngineHarness(spec=geometry).use { h ->
        val r=Random(600301); val shape=PersistentShape(h.clock,ShapeTarget(listOf(randomBody(r)),JoinPolicy.Union),"fuzz"); shape.outline()
        var live=1; var events=0; var drives=0
        fun slot(ids: List<Long>,a: FloatArray,id: Long)=ids.indexOf(id).let { a.copyOfRange(2+12*it,14+12*it) }
        while(events<10000) {
            // D18: a drive keeps the live body count; retargets may birth or kill bodies.
            val drive=r.nextInt(4)==0; val count=if(drive) live else 1+r.nextInt(3); val target=ShapeTarget(List(count) { randomBody(r) },JoinPolicy.Union,GlassStrain(r.nextFloat()*.2f-.1f,r.nextFloat()*.2f-.1f))
            h.now+=r.nextInt(20_000_000)+1; val s=h.clock.stampNow; val ids=shape.slotIds(); val x0=shape.valuesAt(s); val v0=shape.velocitiesAt(s)
            if(drive) shape.drive(target,s) else shape.retarget(target,profile)
            val after=shape.slotIds(); val x1=shape.valuesAt(s); val v1=shape.velocitiesAt(s)
            if(drive) {
                // Contact is C0 by nature: the live chart equals the driven target at the stamp.
                drives++; assertFalse(shape.lastIntentWasRetarget); assertEquals("A drive only purges settled retiring slots",ids.filter { it in after },after); assertArrayEquals(floatArrayOf(target.strain.strain,target.strain.depth),x1.copyOfRange(0,2),0f)
                shape.slotIds(live=true).forEachIndexed { k,id -> assertArrayEquals("event $events",bodyChart(target.bodies[k]),slot(after,x1,id),0f) }
            } else {
                // Every retained edge/radius/strain value and derivative joins exactly at the intent stamp, per slot identity.
                assertTrue(shape.lastIntentWasRetarget); assertArrayEquals(x0.copyOfRange(0,2),x1.copyOfRange(0,2),0f); assertArrayEquals(v0.copyOfRange(0,2),v1.copyOfRange(0,2),1e-3f)
                for(id in ids.filter { it in after }) { assertArrayEquals("event $events",slot(ids,x0,id),slot(after,x1,id),0f); assertArrayEquals("event $events",slot(ids,v0,id),slot(after,v1,id),1e-3f) }
            }
            live=count; if(r.nextInt(3)==0) h.advance(r.nextDouble()*.3)
            events++
        }
        assertTrue(drives>2000)
    }

    @Test fun driveRejectsTopologyChangeAtomically() = EngineHarness(spec=geometry).use { h ->
        val a=pill(0f,0f,90f,40f); val b=pill(100f,0f,90f,40f)
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(a,b),JoinPolicy.Union,keys=listOf("a","b")),"drive"); val before=shape.outline(); val ids=shape.slotIds()
        val s=h.clock.stampNow
        for(bad in listOf(ShapeTarget(listOf(a),JoinPolicy.Union,keys=listOf("a")),ShapeTarget(listOf(a,b,b),JoinPolicy.Union,keys=listOf("a","b","c")),ShapeTarget(listOf(a,b),JoinPolicy.Union,keys=listOf("a","c")),ShapeTarget(listOf(a,b),JoinPolicy.Union)))
            assertThrows(IllegalArgumentException::class.java) { shape.drive(bad,s) }
        assertSame(before,shape.outline()); assertEquals(ids,shape.slotIds()); assertEquals(0,h.clock.activeCount)
        val moved=ShapeTarget(listOf(pill(5f,0f,90f,40f),b),JoinPolicy.Union,keys=listOf("a","b")); h.now+=1_000_000; val later=h.clock.stampNow; shape.drive(moved,later)
        assertEquals(moved.bodies,shape.outlineAt(later).bodies)
        assertThrows("Stale drive stamp",IllegalArgumentException::class.java) { shape.drive(moved,s) }; Unit
    }

    @Test fun retargetAfterDriveKillsWithThatRetargetsProfile() = EngineHarness(spec=geometry).use { h ->
        // D19: a body that dies when a retarget interrupts a drive contracts with the retarget's profile.
        val a=pill(0f,0f,90f,40f); val b=pill(100f,0f,90f,40f)
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(a,b),JoinPolicy.Union),"interrupt"); shape.outline()
        h.now+=1_000_000; shape.drive(ShapeTarget(listOf(a,pill(110f,0f,90f,40f)),JoinPolicy.Union),h.clock.stampNow)
        h.now+=1_000_000; shape.drive(ShapeTarget(listOf(a,pill(120f,0f,90f,40f)),JoinPolicy.Union),h.clock.stampNow)
        h.now+=1_000_000; shape.retarget(ShapeTarget(listOf(a),JoinPolicy.Union),motionProfileId(MotionProfile.Control))
        h.advance(.15); assertEquals(2,shape.outline().bodies.size)
        h.advance(.04); assertEquals("Control (180 ms) has finished the contraction",listOf(a),shape.outline().bodies)
    }

    @Test fun keyedCorrespondenceFollowsKeysNotGeometry() = EngineHarness(spec=geometry).use { h ->
        val bodies=List(3) { pill(it*100f,0f,90f,40f) }
        val shape=PersistentShape(h.clock,ShapeTarget(bodies,JoinPolicy.Union,keys=listOf("a","b","c")),"keyed"); shape.outline(); val ids=shape.slotIds()
        shape.retarget(ShapeTarget(bodies,JoinPolicy.Union,keys=listOf("a","x","c")),profile)
        val after=shape.slotIds(); assertEquals(4,after.size); assertEquals(ids[0],after[0]); assertEquals(ids[2],after[3]); assertTrue(after.containsAll(ids)); assertEquals(1,(after-ids.toSet()).size)
        val plain=PersistentShape(h.clock,ShapeTarget(bodies,JoinPolicy.Union),"plain"); plain.outline(); val plainIds=plain.slotIds()
        plain.retarget(ShapeTarget(listOf(bodies[0],pill(105f,0f,90f,40f),bodies[2]),JoinPolicy.Union),profile); assertEquals("Unkeyed bodies match by geometry",plainIds,plain.slotIds())
        assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(bodies,JoinPolicy.Union,keys=listOf("a","a","c")),profile) }
        assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(bodies,JoinPolicy.Union,keys=listOf("a","c")),profile) }; Unit
    }

    @Test fun concaveFilletAndOutOfChartRadiiRejectBeforeMutation() = EngineHarness(spec=geometry).use { h ->
        val body=pill(0f,0f,100f,40f)
        assertThrows(IllegalArgumentException::class.java) { PersistentShape(h.clock,ShapeTarget(listOf(body)),"fillet") }
        val shape=PersistentShape(h.clock,ShapeTarget(listOf(body),JoinPolicy.Union),"chart"); val before=shape.outline()
        assertThrows("ConcaveFillet needs JoinData first",IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(body),JoinPolicy.ConcaveFillet),profile) }
        // Sums fit Skia's scale, but one radius exceeds half its side: outside the fraction chart, rejected rather than clamped.
        val lopsided=RoundRect(0f,0f,100f,40f,CornerRadius(70f,10f),CornerRadius(10f,10f),CornerRadius.Zero,CornerRadius.Zero)
        assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(lopsided),JoinPolicy.Union),profile) }
        assertThrows(IllegalArgumentException::class.java) { shape.retarget(ShapeTarget(listOf(body),JoinPolicy.Union,GlassStrain(Float.NaN,0f)),profile) }
        assertSame(before,shape.outline()); assertEquals(0,h.clock.activeCount)
        assertTrue(PersistentShape(h.clock,ShapeTarget(emptyList()),"empty fillet").outline().bodies.isEmpty())
    }

    @Test fun consecutiveContoursMoveNoFurtherThanTheirChannels() = EngineHarness(spec=geometry).use { h ->
        val r=Random(600302); val shape=PersistentShape(h.clock,ShapeTarget(listOf(pill(0f,0f,120f,48f)),JoinPolicy.Union),"hausdorff"); shape.outline()
        repeat(300) { sequence ->
            if(sequence%15==0) shape.retarget(ShapeTarget(List(1+r.nextInt(3)) { i -> pill(i*130f+r.nextFloat()*40,r.nextFloat()*40,60f+r.nextFloat()*80,30f+r.nextFloat()*30) },JoinPolicy.Union),profile)
            val a=shape.outline(); h.advance(1.0/120); val b=shape.outline()
            val ca=channels(a); val cb=channels(b); val limit=if(ca.size==cb.size) ca.indices.maxOfOrNull { abs(ca[it]-cb[it]) }?:0f else Float.NaN
            if(!limit.isNaN()) assertTrue("sequence $sequence",hausdorff(a.bodies,b.bodies)<=2*limit+.75f)
        }
    }

    private fun boundary(b: RoundRect,n: Int=96): List<Offset> = List(n) { i ->
        val t=i*(2*PI)/n; val cx=(b.left+b.right)/2; val cy=(b.top+b.bottom)/2; val dx=cos(t).toFloat(); val dy=sin(t).toFloat()
        // Ray-march to the boundary of the rounded rectangle from its centre.
        var lo=0f; var hi=b.width+b.height+1f; repeat(40) { val m=(lo+hi)/2; if(b.contains(Offset(cx+dx*m,cy+dy*m))) lo=m else hi=m }; Offset(cx+dx*lo,cy+dy*lo)
    }
    private fun distanceToRegion(p: Offset,bodies: List<RoundRect>): Float = if(bodies.any { it.contains(p) }) 0f else bodies.flatMap { boundary(it) }.minOfOrNull { (it-p).getDistance() }?:Float.POSITIVE_INFINITY
    private fun hausdorff(a: List<RoundRect>,b: List<RoundRect>): Float {
        val pa=a.filter { it.width>0 && it.height>0 }.flatMap { boundary(it) }; val pb=b.filter { it.width>0 && it.height>0 }.flatMap { boundary(it) }
        return maxOf(pa.maxOfOrNull { distanceToRegion(it,b) }?:0f,pb.maxOfOrNull { distanceToRegion(it,a) }?:0f)
    }
}
