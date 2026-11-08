package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class ExpansionBoundaryPropertyTest {
    @Test fun oneMillionExactCubicExtremaAndHandOffsIncludeFloatMaxBothDirections() {
        val random=Random(600201); val spec=StockUiSpec.expansion
        // max u(1-u)^2 =4/27 at u=1/3; max u^2(1-u)=4/27 at2/3.
        assertTrue(4.0/27<.149)
        repeat(1000000) { i ->
            val p=-spec.band.toDouble()+((1f+spec.band).toDouble()+spec.band)*random.nextDouble(); val q=random.nextDouble()
            val v=(if(i%2==0) 1 else -1)*min(Float.MAX_VALUE.toDouble(),10.0.pow(-6+44.531839419103626*random.nextDouble()))
            val hz=listOf(60,90,120)[i%3]; val sigma=if(i%2==0) 1.0 else 2.0/3
            val T=expansionDuration(spec,1000.0,1000.0,p,v,q,sigma,1.0/hz)
            val lo=-spec.band.toDouble(); val hi=(1f+spec.band).toDouble()
            val plan=expansionPlan(FrameStamp(0,0),spec,T,sigma,listOf(doubleArrayOf(p,v,q,lo,hi)))
            val curve=plan.segment(p,v,q,lo..hi)
            assertTrue("lower seed600201 case$i",curve.extrema.start>=lo); assertTrue("upper seed600201 case$i",curve.extrema.endInclusive<=hi)
            val core=Bezier(spec.core); val tin=plan.tauIn; val tout=plan.tauOut
            assertEquals(p,curve.value(0.0),0.0); assertEquals(v,curve.velocity(0.0),0.0)
            assertEquals(p+(q-p)*core.at(tin/T),curve.value(tin),1e-12)
            assertEquals((q-p)*core.at(tin/T,true)/T,curve.velocity(tin),1e-9)
            assertEquals(q,curve.value(T),0.0); assertEquals(0.0,curve.velocity(T),0.0)
            for(t in listOf(0.0,1.0/hz,2.0/hz,T-tout,T)) assertTrue(curve.value(t) in lo..hi)
        }
    }
    @Test fun rejectedZeroBandAndAdmittedPrecisionFloorHaveFiniteWorstReleases() {
        val stock=StockUiSpec.expansion
        for(band in listOf(0f,Float.MIN_VALUE,Math.nextDown(MinimumContactBand))) {
            val result=uiSpec(InterfaceSettings(),UiSpecData(expansion=stock.copy(band=band)))
            assertTrue(result is SpecResult.Rejected); assertTrue((result as SpecResult.Rejected).reasons.any { "ulp(1f)" in it })
        }
        for(band in listOf(MinimumContactBand,1e-5f,.001f,.05f,.1f)) {
            val accepted=uiSpec(InterfaceSettings(),UiSpecData(expansion=stock.copy(band=band))) as SpecResult.Accepted
            val spec=accepted.spec.expansion
            for(hz in listOf(60,90,120)) for(sigma in listOf(1.0,2.0/3)) for(p in listOf(0.0,1.0)) {
                val v=if(p==0.0) -Float.MAX_VALUE.toDouble() else Float.MAX_VALUE.toDouble(); val q=p
                val T=expansionDuration(spec,1000.0,1000.0,p,v,q,sigma,1.0/hz); val lo=-band.toDouble(); val hi=(1f+band).toDouble()
                val plan=expansionPlan(FrameStamp(0,0),spec,T,sigma,listOf(doubleArrayOf(p,v,q,lo,hi)))
                assertTrue(plan.tauIn>0 && plan.tauIn.isFinite()); val e=plan.segment(p,v,q,lo..hi)
                assertTrue(e.extrema.start>=lo && e.extrema.endInclusive<=hi); assertEquals(v,e.velocity(0.0),0.0); assertEquals(0.0,e.velocity(T),0.0)
            }
        }
    }
    @Test fun fullPlayerEmpiricalStockGateAndSmallObjectTelemetryWitness() {
        val spec=StockUiSpec.expansion; val measured=12.476468497
        var worst=1.0
        for(hz in listOf(60,90,120)) for(sigma in listOf(1.0,2.0/3)) for(multiplier in listOf(1.5,2.0)) for(i in 0..10000) {
            val p=i/10000.0; val v=measured*multiplier
            val T=expansionDuration(spec,1000.0,1000.0,p,v,1.0,sigma,1.0/hz); val approved=min(sigma/120,T/4)
            val plan=expansionPlan(FrameStamp(0,0),spec,T,sigma,listOf(doubleArrayOf(p,v,1.0,-spec.band.toDouble(),(1f+spec.band).toDouble())))
            if(multiplier==1.5) assertEquals(approved,plan.tauIn,0.0) else { worst=min(worst,plan.tauIn/approved); assertTrue(plan.tauIn>=.9*approved) }
        }
        assertEquals(.906598,worst,2e-5)
        val T=expansionDuration(spec,0.0,1000.0,.7,measured*1.5,1.0,1.0,1.0/120)
        val small=expansionPlan(FrameStamp(0,0),spec,T,1.0,listOf(doubleArrayOf(.7,measured*1.5,1.0,-.05,(1f+spec.band).toDouble())))
        assertTrue(small.tauIn<min(1.0/120,T/4)) // A35 B/C telemetry review, never a feel gate.
    }
    @Test fun admittedSteepPluginsStillHaveFiniteRealChannelVelocities() {
        val stock=StockUiSpec.expansion
        for(x in listOf(1e-8f,1e-15f,1e-25f,1e-30f,1e-37f)) for(band in listOf(MinimumContactBand,.05f)) {
            val profile=stock.copy(core=EasingSpec(x,1f,.5f,1f),band=band)
            val accepted=uiSpec(InterfaceSettings(),UiSpecData(expansion=profile)) as SpecResult.Accepted
            for(p in listOf(0.0,1.0)) for(v in listOf(-Float.MAX_VALUE.toDouble(),Float.MAX_VALUE.toDouble())) {
                val q=if(v>0) 1.0 else 0.0; val lo=-band.toDouble(); val hi=(1f+band).toDouble()
                val T=expansionDuration(accepted.spec.expansion,1000.0,1000.0,p,v,q,1.0,1.0/120)
                val plan=expansionPlan(FrameStamp(0,0),profile,T,1.0,listOf(doubleArrayOf(p,v,q,lo,hi)))
                val e=plan.segment(p,v,q,lo..hi)
                for(t in listOf(0.0,plan.tauIn/3,plan.tauIn,T-plan.tauOut,T)) assertTrue(e.velocity(t).toFloat().isFinite())
            }
        }
    }

}
