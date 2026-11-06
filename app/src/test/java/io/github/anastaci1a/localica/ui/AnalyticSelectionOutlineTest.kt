package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AnalyticSelectionOutlineTest {
    @Test fun retainedBodiesProduceTheSameContactsAsFreshGeometryWhenInputsChange() {
        val cached=AnalyticSelectionOutline()
        for(frame in 0..120) {
            val gap=if(frame%3==0)28f else 16f
            val regions=List(12) { i ->
                val dx=if(i==5)sin(frame*.07f)*10f else 0f
                val dy=if(i==5)cos(frame*.09f)*8f else 0f
                SelectionRegion(Rect(i%3*73f+dx,i/3*73f+dy,i%3*73f+72f+dx,i/3*73f+72f+dy),
                    if(frame%5==0 && i==2)8f else 12f)
            }.let { if(frame%7==0)it.reversed() else it }
            val progress=(frame%100)/100f
            assertEquals("frame=$frame",AnalyticSelectionOutline().inspect(regions,gap) { _,_->progress },
                cached.inspect(regions,gap) { _,_->progress })
        }
    }
    @Test fun fadingLobesKeepFilletsNearTheirItems() {
        for(frame in 0..120) {
            val t=frame/120f
            val separation=-4f+20f*t
            val progress=hoverBridgeStrength(true,true,t to t,(1f-t) to (1f-t),1f to 1f)
            val a=Rect(16f,16f,256f,88f)
            val b=Rect(16f,88f+separation,256f,160f+separation)
            val model=AnalyticSelectionOutline()
            val fillets=model.inspect(listOf(SelectionRegion(a,12f),SelectionRegion(b,12f)),16f) { _,_->progress }
            if(separation>2*outlineReach(progress.toDouble(),separation.toDouble(),108.0,1.0))
                assertTrue("Disconnected lobes cannot create fillets at frame $frame",fillets.isEmpty())
            for(fillet in fillets) {
                assertTrue("frame=$frame $fillet",fillet.center.x in 0.0..272.0 && fillet.center.y in 0.0..190.0)
                assertTrue("frame=$frame $fillet",(fillet.a-fillet.b).length()<=8.00001)
                assertTrue("frame=$frame $fillet",abs((fillet.a-fillet.center).length()-fillet.radius)<1e-5)
            }
        }
    }
    @Test fun rollingFilletsStayCappedAndTangentAcrossConnectDisconnectAndOverlap() {
        for (density in listOf(1f,1.75f,2.625f)) for (width in listOf(72f,119f,320f)) for(frame in 0..120) {
            val gap=(-4f+20f*frame/120)*density
            val a=SelectionRegion(Rect(0f,0f,width*density,72f*density),12f*density)
            val b=SelectionRegion(a.bounds.translate(androidx.compose.ui.geometry.Offset(0f,72f*density+gap)),12f*density)
            val model=AnalyticSelectionOutline()
            val fillets=model.inspect(listOf(a,b),16f*density) { _,_->1f }
            for(fillet in fillets) {
                assertTrue(fillet.radius>0 && fillet.radius<=4*density+1e-7)
                assertEquals(0.0,(fillet.a-fillet.center).dot(fillet.tangentA),1e-5*density)
                assertEquals(0.0,(fillet.b-fillet.center).dot(fillet.tangentB),1e-5*density)
            }
        }
    }
    @Test fun reachUsesPresentedGapAndProgressAndVanishesContinuouslyAtBothEnds() {
        for (half in listOf(2.0,24.0,160.0)) {
            assertEquals(0.0,outlineReach(0.0,1.0,half,1.0),0.0)
            assertEquals(0.0,outlineReach(1.0,8.0,half,1.0),0.0)
            for (i in 0..100) {
                val p=i/100.0
                val r=outlineReach(p,1.0,half,1.0)
                assertTrue(r in 0.0..min(4.0,half*.5))
                assertEquals(r,outlineReach(p,1.0,half,1.0),0.0)
            }
            assertTrue(outlineReach(.00001,1.0,half,1.0)<1e-10)
            assertTrue(outlineReach(1.0,7.99999,half,1.0)<1e-10)
        }
    }
    @Test fun circularLobesMeetTheItemTangentiallyAndStayWithinTheirSupport() {
        for (half in listOf(2.0,24.0,160.0)) for (reach in listOf(.001,.1,1.0,4.0).filter { it<=half*.5 }) {
            val lobe=OutlineLobe(0,0,OutlinePoint(80.0,40.0),OutlinePoint(1.0,0.0),half,reach,min(4.0,half*.25))
            for (i in lobe.edges.indices) {
                val a=lobe.edges[i];val b=lobe.edges[(i+1)%lobe.edges.size]
                assertTrue((a.b-b.a).length()<1e-7)
                if(i<2)assertTrue(a.tangent(1.0).dot(b.tangent(0.0))>1-1e-10)
            }
            assertEquals(0.0,lobe.edges.first().tangent(0.0).x,1e-12)
            assertEquals(0.0,lobe.edges[2].tangent(1.0).x,1e-12)
            for (edge in lobe.edges.take(3)) for (i in 0..32) {
                val p=edge.point(i/32.0)
                assertTrue(p.x>=80-1e-7 && p.x<=80+reach+1e-7)
                assertTrue(abs(p.y-40)<=half+1e-7)
            }
            assertTrue(lobe.contains(OutlinePoint(80+reach*.5,40.0)))
            assertFalse(lobe.contains(OutlinePoint(80+reach+.01,40.0)))
        }
    }
    @Test fun roundedBodyDistanceAndMembershipAgreeAtCornersAndStraightSides() {
        val body=OutlineBody(0,0,Rect(0f,0f,80f,80f),12.0)
        for (x in -10..90) for (y in -10..90) {
            val p=OutlinePoint(x.toDouble(),y.toDouble())
            assertEquals(body.distance(p)<=1e-12,body.contains(p))
        }
    }
}

