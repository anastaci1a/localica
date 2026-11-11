package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class ExpansionClosurePropertyTest {
    private val field=MotionFloat::class.java.getDeclaredField("segment").apply { isAccessible=true }
    @Test fun oneHundredThousandAdmittedSpecChainsRemainClosedAcrossEightCatches() {
        val r=Random(600203); var accepted=0; var bridgeCatches=0
        EngineHarness().use { h ->
            while(accepted<100000) {
                val band=if(accepted%4==0) MinimumContactBand else (.0001+r.nextDouble()*.0999).toFloat()
                val core=when(accepted%4) {
                    0 -> StockUiSpec.expansion.core
                    1 -> EasingSpec(.01f+r.nextFloat()*.98f,r.nextFloat()*1.1f,.01f+r.nextFloat()*.98f,r.nextFloat()*1.1f)
                    2 -> EasingSpec(.01f+r.nextFloat()*.98f,r.nextFloat(),.01f+r.nextFloat()*.98f,r.nextFloat())
                    else -> EasingSpec(10.0.pow(-8-r.nextDouble()*29).toFloat(),1f,.5f,1f)
                }
                val candidate=StockUiSpec.expansion.copy(core=core,band=band,referenceDurationMs=(16+r.nextInt(1985)).toFloat(),floorMs=(16+r.nextInt(1985)).toFloat(),positionFloor=.001f+r.nextFloat()*.999f,velocityKnee=.001f+r.nextFloat()*20,velocityExponent=.01f+r.nextFloat()*4,sizeExponent=.01f+r.nextFloat()*5,deadBand=r.nextFloat(),stationaryOpenAt=r.nextFloat())
                val result=uiSpec(InterfaceSettings(),UiSpecData(expansion=candidate),ExpansionGeometryDomain(1f,200f,1f/60))
                if(result !is SpecResult.Accepted) continue
                val spec=result.spec.expansion; val hz=listOf(60,90,120)[accepted%3]; val access=if(accepted%2==0) MotionAccess.Standard else MotionAccess.Faster
                h.clock.configure(EngineContext(1f/hz,1f,access),StockUiSpec)
                val c=ExpandCollapse(h.clock,spec,r.nextBoolean(),"closure$accepted")
                c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(1f,0f,100f,100f),1f))
                repeat(8) { cycle ->
                    val old=field.get(c.phase) as Trajectory
                    if(old is Eased) {
                        assertTrue("old lower seed600203 chain$accepted cycle$cycle",old.extrema.start>=-band)
                        assertTrue("old upper seed600203 chain$accepted cycle$cycle",old.extrema.endInclusive<=(1f+band).toDouble())
                        val coreLaw=unitCurve(spec.core); val x=old.value(0.0); val v=old.velocity(0.0); val q=old.value(old.duration); val tau=old.tauIn
                        val b=x+(q-x)*coreLaw.at(tau/old.duration); val w=(q-x)*coreLaw.at(tau/old.duration,true)/old.duration
                        val C=tau*v; val B=3*(b-x)-2*C-tau*w; val A=-2*(b-x)+C+tau*w
                        val peaks=quadraticRoots(3*A,2*B,C).filter { it>0 && it<1 }
                        val t=if(cycle%2==0 && peaks.isNotEmpty()) { bridgeCatches++; tau*peaks[r.nextInt(peaks.size)] } else r.nextDouble()*old.duration
                        // An actual catch is a FrameStamp with integer nanoseconds. Sub-nanosecond
                        // extrema still receive exact containment checks, but cannot be input times.
                        h.now=old.plan!!.stamp.nanos+(t*1e9).roundToLong()
                    }
                    val stamp=h.clock.stampNow; val before=c.phase.sampleAt(stamp)
                    c.contactBegin(stamp); assertEquals(before,c.phase.sampleAt(stamp))
                    if(cycle==0 || r.nextInt(5)==0) { h.now+=1; c.contactMove((r.nextFloat()-.5f)*2,h.clock.stampNow) }
                    val time=h.clock.stampNow; val value=c.phase.valueAt(time)
                    val magnitude=when(cycle%4) { 0 -> Float.MAX_VALUE; 1 -> 10.0.pow(-8+r.nextDouble()*46).toFloat().coerceAtMost(Float.MAX_VALUE); 2 -> 0f; else -> r.nextFloat()*1000 }
                    val velocity=if(r.nextBoolean()) magnitude else -magnitude
                    val decision=try { c.contactRelease(velocity,time) } catch(t: Throwable) { throw AssertionError("seed600203 chain$accepted cycle$cycle core$core band$band state${c.phase.sampleAt(time)} velocity$velocity",t) }
                    assertEquals(value,c.phase.valueAt(time),0f); assertEquals(decision.handOffVelocity,c.phase.velocityAt(time),0f)
                    val next=field.get(c.phase) as Trajectory
                    if(next is Eased) { assertTrue(next.extrema.start>=-band); assertTrue(next.extrema.endInclusive<=(1f+band).toDouble()); assertEquals(0.0,next.velocity(next.duration),0.0) }
                }
                c.dispose(); accepted++
            }
        }
        assertEquals(100000,accepted); assertTrue(bridgeCatches>100000)
    }
    @Test fun twentyThousandSpeedDurationAndHeldChainsCloseOnTheirLatestBasis() {
        val r=Random(812162); val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,3000f,1f/60)) as SpecResult.Accepted).spec; val band=spec.expansion.band
        fun rect()=r.nextFloat()*1000f to r.nextFloat()*1000f
        fun basis(): ExpansionBasis { val (a,b)=rect(); val (c,d)=rect(); val (w,h)=rect(); val (x,y)=rect()
            return ExpansionBasis(Rect(a,b,a+w,b+h),Rect(c,d,c+x,d+y),1f,if(r.nextBoolean()) null else r.nextFloat()*3000f) }
        var holds=0
        EngineHarness(spec=spec).use { h ->
            repeat(20000) { chain ->
                h.clock.configure(EngineContext(1f/listOf(60,90,120)[chain%3],1000f,if(chain%2==0) MotionAccess.Standard else MotionAccess.Faster),spec)
                val c=ExpandCollapse(h.clock,spec.expansion,r.nextBoolean(),"held$chain"); var latest=basis(); c.setBasis(latest)
                val rect=MotionRect(h.clock,latest.startBounds,EdgeRanges(3000f,3000f,3000f,3000f)); c.bind(rect)
                c.updateSpeed(.25f+r.nextFloat()*3.75f); c.target(!c.expanded)
                repeat(8) { cycle ->
                    val label="seed812162 chain$chain cycle$cycle"
                    h.advance(r.nextDouble()*.3)
                    val old=field.get(c.phase) as Trajectory
                    if(old is Eased) { assertTrue(label,old.extrema.start>=-band); assertTrue(label,old.extrema.endInclusive<=(1f+band).toDouble()); assertEquals(label,0.0,old.velocity(old.duration),0.0) }
                    if(cycle%2==0) {
                        c.hold(); holds++; val stamp=h.clock.stampNow; val frozen=listOf(c.phase.valueAt(stamp))+rect.channels.map { it.valueAt(stamp) }
                        // Targets, measurement and speed arriving while the home owns presentation wait for release.
                        if(r.nextBoolean()) c.target(r.nextBoolean())
                        if(r.nextBoolean()) { latest=basis(); c.setBasis(latest) }
                        if(r.nextBoolean()) c.updateSpeed(.25f+r.nextFloat()*3.75f)
                        h.advance(r.nextDouble()*.5); val later=h.clock.stampNow
                        assertEquals(label,frozen,listOf(c.phase.valueAt(later))+rect.channels.map { it.valueAt(later) })
                        assertFalse(label,c.retirementReady); c.release()
                        assertEquals(label,frozen,listOf(c.phase.valueAt(later))+rect.channels.map { it.valueAt(later) })
                    } else when(r.nextInt(3)) {
                        0 -> c.target(!c.expanded)
                        1 -> { latest=basis(); c.setBasis(latest) }
                        else -> c.updateSpeed(.25f+r.nextFloat()*3.75f)
                    }
                }
                h.advance(30.0); val end=h.clock.stampNow
                assertTrue("seed812162 chain$chain",c.phase.settled && rect.channels.all { it.settled } && c.retirementReady)
                assertEquals("seed812162 chain$chain",latest.rect(if(c.expanded) 1.0 else 0.0),rect.valueAt(end))
                c.dispose(); assertEquals(0,h.clock.activeCount)
            }
        }
        assertEquals(80000,holds)
    }
}
