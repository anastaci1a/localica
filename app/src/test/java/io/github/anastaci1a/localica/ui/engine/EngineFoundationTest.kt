package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*


class EngineFoundationTest {
    private val profile=motionProfileId(MotionProfile.Control)
    private fun close(a: Double,b: Double,epsilon: Double=1e-8)=assertEquals(a,b,epsilon*maxOf(1.0,abs(a),abs(b)))
    @Test fun tenThousandEasedJoinsAndProfileTimeScaling() {
        val r=Random(600101)
        repeat(10000) {
            val x=r.nextDouble()*20-10; val q=r.nextDouble()*20-10; val v=r.nextDouble()*200-100; val T=.04+r.nextDouble(); val e=EasingSpec(.2f,0f,.2f,1f)
            val a=Eased(x,v,q,e,T); val sigma=2.0/3; val f=Eased(x,v/sigma,q,e,T*sigma,sigma)
            close(x,a.value(0.0)); close(v,a.velocity(0.0)); close(q,a.value(T)); close(0.0,a.velocity(T))
            for(t in listOf(a.tauIn,T-a.tauOut,T)) {
                val h=1e-9
                close(a.value(t-h),a.value(t+h),1e-5)
                close(a.velocity(t-h),a.velocity(t+h),1e-4)
            }
            repeat(10) { j -> val t=T*j/10; close(a.value(t),f.value(t*sigma)); close(a.velocity(t)/sigma,f.velocity(t*sigma)) }
        }
    }
    @Test fun dampedIndependentClosedFormsGuardAndFiniteTail() {
        val r=Random(600102)
        repeat(10000) { i ->
            val x=r.nextDouble()*2-1; val v=r.nextDouble()*20-10; val w=1+r.nextDouble()*40; val z=listOf(.01,.2,1.0,2.0,10.0,1.0-1e-8,1.0+1e-8)[i%7]
            val d=Damped(x,v,0.0,w,z,1.0,1.0/120)
            assertTrue(d.duration.isFinite() && d.duration>=1.0/60)
            val t=min(.1,d.duration-d.tauOut)*r.nextDouble()
            fun reference(time: Double): Double { val t=time; return when { z<1 -> { val wd=w*sqrt(1-z*z); exp(-z*w*t)*(x*cos(wd*t)+(v+z*w*x)/wd*sin(wd*t)) }; z==1.0 -> (x+(v+w*x)*t)*exp(-w*t); else -> { val s=w/(z+sqrt(z*z-1)); val f=w*(z+sqrt(z*z-1)); val A=(v+f*x)/(f-s); val B=-(v+s*x)/(f-s); A*exp(-s*t)+B*exp(-f*t) } } }
            close(reference(t),d.value(t),1e-7)
            val h=1e-5/w; close((reference(t+h)-reference(t-h))/(2*h),d.velocity(t),1e-4)
            close(0.0,d.value(d.duration)); close(0.0,d.velocity(d.duration))
            val join=d.duration-d.tauOut; close(d.raw(join,false),d.value(join)); close(d.raw(join,true),d.velocity(join))
            // The Hermite perturbation is bounded by the admitted displacement envelope times exp(lambdaSlow*tau).
            val lambda=if(z>1) w/(z+sqrt(z*z-1)) else z*w
            assertTrue(lambda*d.tauOut<=1+1e-12)
            repeat(20) { j -> val s=join+d.tauOut*j/20; val bound=.0003*exp(lambda*d.tauOut); assertTrue("tail case$i",abs(d.value(s)-d.raw(s,false))<=bound+1e-10) }
        }
        val slow=Damped(1.0,0.0,0.0,1.0,2.0,1.0,1.0/60)
        close(.6303600222780178,slow.raw(2.0,false)); val bound=2/sqrt(3.0)*exp(-(2-sqrt(3.0))*2); close(.675665397501329,bound,1e-6)
        assertTrue(slow.duration>2)
        for(hz in listOf(60,90,120)) for(x in listOf(1e-20,.00001,.0002999,.0003001)) {
            val tiny=Damped(x,0.0,0.0,10.0,1.0,1.0,1.0/hz)
            assertTrue(tiny.duration>=2.0/hz); assertEquals(x,tiny.value(0.0),0.0); assertEquals(0.0,tiny.value(tiny.duration),0.0)
            if(x<.0003) assertEquals(0.0,tiny.envelopeSeconds,0.0)
        }
        assertEquals(0.0,Damped(0.0,0.0,0.0,10.0,1.0,1.0,1.0/60).duration,0.0)
    }
    @Test fun oneMillionIntentJoinsAndStaleDriveRejection() {
        EngineHarness().use { h ->
            val m=MotionFloat(h.clock,0f,10f); val r=Random(600103)
            repeat(10000) {
                repeat(100) { j ->
                    h.now+=r.nextInt(1_000_000)+1
                    val s=h.clock.stampNow; val before=m.sampleAt(s); val target=r.nextFloat()*10-5
                    m.retarget(target,profile); assertEquals(before,m.sampleAt(s))
                    if(j%20==0) { val epoch=m.epoch.intValue; m.retarget(target,profile); assertEquals(epoch,m.epoch.intValue) }
                }
                h.advance(.001)
            }
            val s=h.clock.stampNow; m.drive(.2f,s); h.now+=10_000_000; m.drive(.4f,h.clock.stampNow)
            close(20.0,m.velocityAt(h.clock.stampNow).toDouble(),1e-5)
            val before=m.sampleAt(h.clock.stampNow)
            assertThrows(IllegalArgumentException::class.java) { m.drive(1f,FrameStamp(s.nanos-1,s.sequence)) }
            assertEquals(before,m.sampleAt(h.clock.stampNow))
            m.release(1f,profile,-3f); assertEquals(-3f,m.velocityAt(h.clock.stampNow),0f)
            m.dispose(); assertEquals(0,h.clock.activeCount)
        }
    }
    @Test fun clockSleepsEpochReadsAreLazyAndOldFrameSurvivesFreshInput() {
        EngineHarness().use { h ->
            assertEquals(0,h.awaits); val m=MotionFloat(h.clock,0f,1f)
            var reads=0; Snapshot.observe({ reads++ },null) { m.valueAt(h.clock.stampNow) }; assertEquals(0,reads)
            Snapshot.observe({ reads++ },null) { m.value() }; assertEquals(1,reads)
            m.retarget(1f,profile); assertEquals(1,h.awaits)
            reads=0; Snapshot.observe({ reads++ },null) { m.value() }; assertEquals(2,reads)
            val prior=h.clock.presentedStamp; val old=m.valueAt(prior); h.now+=1_000_000; m.retarget(.5f,profile)
            assertEquals(old,m.valueAt(prior),0f)
            val sequence=h.clock.presentedStamp.sequence; h.advance(.4); assertEquals(sequence+1,h.clock.presentedStamp.sequence)
            assertTrue(m.settled); assertEquals(0,h.clock.activeCount)
            val epoch=m.epoch.intValue; val awaiting=h.awaits; h.now+=10_000_000; assertEquals(awaiting,h.awaits); assertEquals(epoch,m.epoch.intValue)
            val observed=mutableListOf<Any>(); Snapshot.observe({ observed+=it },null) { m.value() }; assertEquals(1,observed.size)
            h.now=0; assertTrue(h.clock.stampNow.nanos>=h.clock.presentedStamp.nanos)
            m.dispose()
        }
    }
    @Test fun accessChangesAndObserversCannotMutate() {
        EngineHarness().use { h ->
            val m=MotionFloat(h.clock,0f,1f); m.retarget(1f,profile); h.now+=20_000_000; val s=h.clock.stampNow; val prior=m.sampleAt(s)
            h.clock.configure(EngineContext(1f/120,1000f,MotionAccess.Faster),StockUiSpec); assertEquals(prior,m.sampleAt(s))
            assertThrows(IllegalStateException::class.java) { h.clock.observe { m.retarget(.5f,profile) } }
            h.clock.configure(EngineContext(1f/120,1000f,MotionAccess.Reduced),StockUiSpec); assertEquals(1f,m.value(),0f); assertTrue(m.settled); assertEquals(0,h.clock.activeCount)
            m.dispose()
        }
    }
    @Test fun segmentStartedAfterTheDispatchedFrameDoesNotEndWithZeroDurationScale() {
        EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f/120,1000f,MotionAccess.Standard,0f),StockUiSpec)
            val m=MotionFloat(h.clock,0f,1f); val frame=h.clock.presentedStamp; h.now+=5_000_000; m.retarget(1f,profile)
            assertFalse(m.endsAt(frame)); m.finish(h.clock.stampNow); assertTrue(m.settled)
            m.dispose()
        }
    }
    @Test fun curveProductChainAndBoundaryDerivatives() {
        val r=Random(600104)
        repeat(10000) {
            val a=Curve.linear(r.nextFloat(),r.nextFloat()); val b=Curve.softWindow(.1f,.9f); val c=a*b; val d=a.compose(b)
            val p=r.nextFloat()*1.1f-.05f
            assertEquals(a.slope(p)*b.at(p)+a.at(p)*b.slope(p),c.slope(p),1e-5f)
            assertEquals(a.slope(b.at(p))*b.slope(p),d.slope(p),1e-5f)
            if(p in .11f.. .89f) assertEquals((d.at(p+.0001f)-d.at(p-.0001f))/.0002f,d.slope(p),.003f)
            assertEquals(0f,b.slope(.1f),0f); assertEquals(0f,b.slope(.9f),0f)
        }
        assertThrows(IllegalArgumentException::class.java) { Curve.window(1f,1f) }
    }
    @Test fun atomicRectAndColorRejectWithoutAnyMutationAndBindingIsExclusive() {
        EngineHarness().use { h ->
            val rect=MotionRect(h.clock,Rect(0f,0f,100f,100f),EdgeRanges(100f,100f,100f,100f))
            rect.retarget(Rect(20f,40f,200f,300f),profile); h.now+=30_000_000
            val before=rect.valueAt(h.clock.stampNow)
            assertThrows(IllegalArgumentException::class.java) { rect.retarget(Rect(1f,2f,3f,Float.NaN),profile) }; assertEquals(before,rect.valueAt(h.clock.stampNow))
            rect.claim(this); assertThrows(IllegalStateException::class.java) { rect.drive(Rect.Zero,h.clock.stampNow) }; assertThrows(IllegalStateException::class.java) { rect.retarget(Rect.Zero,profile) }
            val color=MotionColor(h.clock,Color.Red)
            color.retarget(Color.Blue,profile); val sample=color.valueAt(h.clock.stampNow)
            assertThrows(IllegalArgumentException::class.java) { color.retarget(Color.Unspecified,profile) }; assertEquals(sample,color.valueAt(h.clock.stampNow))
            val bounded=MotionFloat(h.clock,.5f,1f,0.0..1.0); bounded.drive(1f,h.clock.stampNow); assertEquals(0f,bounded.velocityAt(h.clock.stampNow),0f)
            val edge=MotionFloat(h.clock,0f,1f); val i=edge.prepare(1f,profile)!!; edge.retarget(.5f,profile); val old=bounded.target
            assertThrows(IllegalStateException::class.java) { commitAtomic(listOf(bounded to bounded.prepare(.5f,profile,i.stamp)!!,edge to i)) }; assertEquals(old,bounded.target,0f)
            rect.dispose(); color.dispose(); edge.dispose(); bounded.dispose()
        }
    }
    @Test fun scalarSamplingAllocatesNoPerReadObjectsAfterWarmup() {
        EngineHarness().use { h ->
            val m=MotionFloat(h.clock,0f,1f); m.retarget(1f,profile)
            val api=Class.forName("com.sun.management.ThreadMXBean"); val bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null); api.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(bean,true); val allocated=api.getMethod("getThreadAllocatedBytes",Long::class.javaPrimitiveType)
            var total=0f; repeat(100000) { total+=m.value() }; val id=Thread.currentThread().id
            val before=allocated.invoke(bean,id) as Long; repeat(1000000) { total+=m.value() }; val bytes=(allocated.invoke(bean,id) as Long)-before
            assertTrue(total.isFinite()); assertTrue("allocated $bytes bytes",bytes<=1024); m.dispose()
        }
    }
}
