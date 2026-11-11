package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class RandomBasisMappedChannelTest {
    private val segment=MotionFloat::class.java.getDeclaredField("segment").apply { isAccessible=true }
    private fun randomBasis(r: Random): ExpansionBasis { val x=r.nextFloat()*100; val y=r.nextFloat()*100; return ExpansionBasis(Rect(x,y,x+100,y+100),Rect(x+r.nextFloat()*100,y+r.nextFloat()*100,x+300+r.nextFloat()*100,y+300+r.nextFloat()*100),1f) }
    @Test fun tenThousandBasisRetargetContactAndAccessTransitionsPreserveMappedJoins() {
        val r=Random(600202)
        EngineHarness().use { h ->
            var basis=randomBasis(r); val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false); c.setBasis(basis)
            val rect=MotionRect(h.clock,basis.startBounds,EdgeRanges(1000f,1000f,1000f,1000f)); c.bind(rect)
            val ids=rect.channels.map { it.id }; val maps=listOf(Curve.window(.1f,.9f),Curve.softWindow(.2f,.8f),Curve.linear(20f,100f))
            repeat(10000) { i ->
                h.advance(1.0/listOf(60,90,120)[i%3]); val stamp=h.clock.stampNow
                val p=c.phase.sampleAt(stamp); val edges=rect.channels.map { it.sampleAt(stamp) }; val mapped=maps.map { it.at(p.value) to it.slope(p.value)*p.velocity }
                when(i%5) {
                    0 -> c.target(r.nextBoolean())
                    1 -> { basis=randomBasis(r); c.setBasis(basis) }
                    2 -> { h.clock.configure(EngineContext(1f/listOf(60,90,120)[i%3],1000f,MotionAccess.Standard,speed=if(i%2==0) 1f else 1.5f),StockUiSpec); c.target(!c.expanded) }
                    3 -> { basis=basis.copy(density=if(basis.density==1f) 2f else 1f); c.setBasis(basis) }
                    else -> c.target(!c.expanded,ExpansionReason.Restore)
                }
                assertEquals(p,c.phase.sampleAt(stamp)); assertEquals(edges,rect.channels.map { it.sampleAt(stamp) }); val after=c.phase.sampleAt(stamp)
                assertEquals(mapped,maps.map { it.at(after.value) to it.slope(after.value)*after.velocity }); assertEquals(ids,rect.channels.map { it.id })
                val trajectory=segment.get(c.phase) as Trajectory
                if(trajectory is Eased) {
                    val plan=trajectory.plan!!; rect.channels.forEach { assertSame(plan,(segment.get(it) as Eased).plan) }
                    val p0=trajectory.value(0.0); val v0=trajectory.velocity(0.0); val start=doubleArrayOf(basis.startBounds.left.toDouble(),basis.startBounds.top.toDouble(),basis.startBounds.right.toDouble(),basis.startBounds.bottom.toDouble()); val end=doubleArrayOf(basis.endBounds.left.toDouble(),basis.endBounds.top.toDouble(),basis.endBounds.right.toDouble(),basis.endBounds.bottom.toDouble()); val delta=DoubleArray(4) { end[it]-start[it] }
                    val starts=rect.channels.map { segment.get(it) as Eased }; val residual=DoubleArray(4) { starts[it].value(0.0)-((1-p0)*start[it]+p0*end[it]) }; val affine=(0..3).all { abs(residual[it])<1e-5 && abs(starts[it].velocity(0.0)-delta[it]*v0)<1e-5 }
                    for(f in listOf(0.0,.1,.3,.7,1.0)) {
                        val t=if(f==1.0) ceil(plan.duration*1e9).toLong() else (plan.duration*f*1e9).toLong(); val s=FrameStamp(plan.stamp.nanos+t,plan.stamp.sequence)
                        val sample=c.phase.sampleAt(s)
                        for(j in 0..3) {
                            val edge=rect.channels[j].sampleAt(s); val expected=(1-sample.value)*start[j]+sample.value*end[j]; val error=edge.value-expected
                            assertTrue("residual seed600202 case$i edge$j",abs(error)<=abs(residual[j])+.05*rect.channels[j].range+1e-5*rect.channels[j].range)
                            if(affine || f==1.0) { assertEquals(expected,edge.value.toDouble(),1e-5*rect.channels[j].range); assertEquals(delta[j]*sample.velocity,edge.velocity.toDouble(),1e-5*rect.channels[j].range) }
                        }
                    }
                }
                if(i%7==0) {
                    val begin=h.clock.stampNow; val before=c.phase.sampleAt(begin); val physical=rect.channels.map { it.sampleAt(begin) }
                    c.contactBegin(begin); assertEquals(before,c.phase.sampleAt(begin)); assertEquals(physical,rect.channels.map { it.sampleAt(begin) })
                    val frozen=basis; h.now+=10_000_000; c.contactMove((r.nextFloat()-.5f)*frozen.edgeTravelPx,h.clock.stampNow)
                    h.now+=10_000_000; c.contactMove((r.nextFloat()-.5f)*frozen.edgeTravelPx,h.clock.stampNow)
                    if(i%3==0) { val pending=randomBasis(r); c.setBasis(pending); assertEquals(pending,c.pendingBasis); basis=pending }
                    val releaseStamp=h.clock.stampNow; val initial=c.phase.sampleAt(releaseStamp); val positions=rect.channels.map { it.valueAt(releaseStamp) }
                    val decision=c.contactRelease((r.nextFloat()-.5f)*4*frozen.edgeTravelPx,releaseStamp)
                    assertEquals(initial.value,c.phase.valueAt(releaseStamp),0f); assertEquals(decision.handOffVelocity,c.phase.velocityAt(releaseStamp),0f)
                    assertEquals(positions,rect.channels.map { it.valueAt(releaseStamp) }); assertFalse(c.inContact); assertNull(c.pendingBasis)
                    rect.channels.forEachIndexed { j,m -> assertEquals(frozen.delta(j)*decision.handOffVelocity,m.velocityAt(releaseStamp).toDouble(),.01) }
                }
                if(i%20==0) { h.advance(2.0); assertTrue(c.phase.settled); assertTrue(rect.channels.all { it.settled }) }
            }
            h.advance(2.0); c.dispose(); assertEquals(0,h.clock.activeCount)
        }
    }
}
