package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.GlassAppearance
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class UiSpecTest {
    private val settings = InterfaceSettings()
    private fun accepted(data: UiSpecData? = null, config: InterfaceSettings = settings) =
        (uiSpec(config,data) as SpecResult.Accepted).spec

    @Test fun stockMatchesIndependentSourceValueTable() {
        val stock=accepted()
        assertEquals(Space.entries.toSet(),stock.space.keys)
        assertEquals(listOf(1f,4f,8f,12f,16f,24f,28f),Space.entries.map { stock.space.getValue(it).value })
        assertEquals(listOf(8f,16f,20f,24f,28f),Radius.entries.dropLast(1).map { stock.radius.getValue(it).value })
        assertEquals(listOf(64f,51.2f,32f,64f,28f,24f,56f,48f,24f),ControlSize.entries.map { stock.control.getValue(it).value })
        assertEquals(listOf(180,270,220,220,300,240,300,165,300,225),MotionProfile.entries.take(10).map { (stock.motion.getValue(it) as MotionProfileSpec).durationMs })
        assertEquals(MarqueeSpec(1800,220,260,30f,EasingSpec(1f/3,0f,2f/3,1f)),stock.motion[MotionProfile.Marquee])
        assertEquals(ListMotionSpec(350,500,120,EasingSpec(.4f,0f,.2f,1f),EasingSpec(0f,0f,1f,1f)),stock.motion[MotionProfile.Scroll])
        assertEquals(2.471915f,stock.expansion.core.maxSlope,1e-5f)
        assertEquals(.0003f,SettleThreshold,0f); assertEquals(.003f,SettleVelocityThreshold,0f)
        assertEquals(515.0463f,stock.expansion.referenceDurationMs,0f)
        assertEquals(.05f,stock.expansion.band,0f)
        assertEquals(22.8f,stock.clearance.getValue(Clearance.ShadowInset).value,.00001f)
    }
    @Test fun rejectsNaNZeroDurationAndEveryMalformedFieldWithoutThrowing() {
        val r=Random(600000)
        val bad=listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1f)
        repeat(10000) { index ->
            val value=bad[r.nextInt(bad.size)]
            val data=when(index%8) {
                0 -> UiSpecData(space=mapOf(Space.Base to value.dp))
                1 -> UiSpecData(radius=mapOf(Radius.Card to value.dp))
                2 -> UiSpecData(control=mapOf(ControlSize.Compact to value.dp))
                3 -> UiSpecData(clearance=mapOf(Clearance.LiftMax to value.dp))
                4 -> UiSpecData(motion=mapOf(MotionProfile.Control to MotionProfileSpec(0,EasingSpec(0f,0f,.2f,1f))))
                5 -> UiSpecData(spring=mapOf(SpringProfile.Page to SpringProfileSpec(value,380f)))
                6 -> UiSpecData(expansion=StockUiSpec.expansion.copy(velocityKnee=value))
                else -> UiSpecData(material=mapOf(MaterialRole.Glass to StockUiSpec.material.getValue(MaterialRole.Glass).copy(blur=value)))
            }
            val a=uiSpec(settings,data)
            assertTrue("seed600000 case$index $a",a is SpecResult.Rejected)
            assertEquals(a,uiSpec(settings,data))
        }
        for(d in listOf(15,16,2000,2001)) assertEquals(d in 16..2000,uiSpec(settings,UiSpecData(motion=mapOf(MotionProfile.Control to MotionProfileSpec(d,EasingSpec(.4f,0f,.2f,1f))))) is SpecResult.Accepted)
        assertTrue(uiSpec(settings,UiSpecData(radius=mapOf(Radius.Pill to 1.dp))) is SpecResult.Rejected)
    }
    @Test fun acceptedMapsAreDefensiveAndUnmodifiableIncludingStock() {
        val input=mutableMapOf(Space.Base to 13.dp)
        val a=accepted(UiSpecData(space=input)); input[Space.Base]=14.dp
        assertEquals(13.dp,a.space[Space.Base])
        for(map in listOf(a.space,StockUiSpec.space,StockUiSpec.radius,StockUiSpec.motion,StockUiSpec.spring,StockUiSpec.material)) {
            try { (map as MutableMap<*,*>).clear(); fail("Installed map must be immutable") } catch(expected: UnsupportedOperationException) { }
        }
        assertEquals(12.dp,accepted().space[Space.Base])
    }
    @Test fun densityAndFontScaleResolutionPreserveFloatGeometry() {
        for(d in listOf(.75f,1f,1.75f,2.625f,4f)) for(font in listOf(1f,1.3f,2f)) {
            val resolved=ResolvedUiSpec(accepted(),Density(d,font))
            for(space in Space.entries) assertEquals(StockUiSpec.space.getValue(space).value*d,resolved.px(space),0f)
            assertEquals(51.2f*d,resolved.px(ControlSize.Compact),0f)
            assertEquals(50f,resolved.radiusPx(Radius.Pill,100f),0f)
        }
        for(d in listOf(Float.NaN,0f,-1f,Float.POSITIVE_INFINITY)) try { ResolvedUiSpec(accepted(),Density(d)); fail("Invalid density $d") } catch(expected: IllegalArgumentException) { }
        try { ResolvedUiSpec(accepted(UiSpecData(space=mapOf(Space.Panel to Float.MAX_VALUE.dp))),Density(4f)); fail("Resolved px overflow") } catch(expected: IllegalArgumentException) { }
    }
    @Test fun reducedDominatesFasterAndMaterialOverridesResolveOneSpread() {
        assertEquals(MotionAccess.Reduced,accepted(UiSpecData(access=MotionAccess.Standard),settings.copy(reducedMotion=true,fasterMotion=true)).access)
        assertEquals(MotionAccess.Faster,accepted(config=settings.copy(fasterMotion=true)).access)
        val m=StockUiSpec.material.getValue(MaterialRole.Glass).copy(shadowSpread=.1f)
        assertEquals(8.8f,accepted(UiSpecData(material=mapOf(MaterialRole.Glass to m))).clearance.getValue(Clearance.ShadowInset).value,.00001f)
        assertEquals(2f,accepted(config=settings.copy(glass=GlassAppearance(shapeDamping=2f)) ).material.getValue(MaterialRole.Glass).shapeDamping,0f)
    }
    @Test fun everyPositionSpringIsAdmittedAndSlowOverrideRejected() {
        for((key,spring) in accepted().spring) if(key!=SpringProfile.Strain) {
            val horizon=springHorizon(spring)
            assertTrue("$key $horizon",horizon.isFinite() && horizon<=2)
        }
        assertTrue(uiSpec(settings,UiSpecData(spring=mapOf(SpringProfile.Page to SpringProfileSpec(2f,1f)))) is SpecResult.Rejected)
        // A2.2c deliberately preserves the user's decorative low damping; no two-second clamp.
        assertTrue(uiSpec(settings.copy(glass=GlassAppearance(shapeDamping=2f))) is SpecResult.Accepted)
        val page=StockUiSpec.spring.getValue(SpringProfile.Page)
        val lambda=.85*sqrt(380.0)
        val tau=minOf(springHorizon(page)/8,.064,1/lambda)
        assertEquals(.0603517,tau,.0000001); assertTrue(lambda*tau<=1.000000001)
    }
    @Test fun easingSupremumBoundsIndependentDerivativeIncludingRemovableTangents() {
        val curves=listOf(EasingSpec(0f,0f,.2f,1f),EasingSpec(.4f,0f,.2f,1f),StockUiSpec.expansion.core,EasingSpec(1f/3,0f,2f/3,1f),EasingSpec(0f,0f,1f,1f))
        for(e in curves) for(i in 1..10000) {
            val t=i/10001.0
            fun derivative(a: Float,b: Float)=3*(1-t)*(1-t)*a+6*(1-t)*t*(b-a)+3*t*t*(1-b)
            val slope=abs(derivative(e.y1,e.y2)/derivative(e.x1,e.x2))
            assertTrue("$e at$t slope$slope bound${e.maxSlope}",slope<=e.maxSlope.toDouble()+1e-5)
        }
        assertTrue(uiSpec(settings,UiSpecData(motion=mapOf(MotionProfile.Control to MotionProfileSpec(180,EasingSpec(0f,1f,.2f,1f))))) is SpecResult.Rejected)
    }
    @Test fun exactEasingExtremaBoundOneHundredThousandIndependentDenseCurves() {
        val random=Random(600002)
        repeat(100000) {
            val e=EasingSpec(random.nextFloat(),random.nextFloat()*6-3,random.nextFloat(),random.nextFloat()*6-3)
            val extrema=easingExtrema(e)
            var min=0.0; var max=1.0
            for(i in 0..64) {
                val t=i/64.0; val u=1-t
                val value=3*u*u*t*e.y1+3*u*t*t*e.y2+t*t*t
                assertTrue(value>=extrema.start-1e-12 && value<=extrema.endInclusive+1e-12)
                min=minOf(min,value); max=maxOf(max,value)
            }
            val curvature=6*maxOf(abs(e.y2.toDouble()-2*e.y1),abs(1-2*e.y2.toDouble()+e.y1))
            val bound=curvature/(8*64*64)+1e-12
            assertTrue(min-extrema.start<=bound)
            assertTrue(extrema.endInclusive-max<=bound)
        }
        val overshoot=EasingSpec(.25f,2f,.75f,2f)
        assertTrue(easingExtrema(overshoot).endInclusive>1.625)
        assertTrue(uiSpec(settings,UiSpecData(expansion=StockUiSpec.expansion.copy(core=overshoot))) is SpecResult.Rejected)
        assertTrue("Unbounded scalar profiles retain legitimate plugin back-easing",uiSpec(settings,UiSpecData(motion=mapOf(MotionProfile.Control to MotionProfileSpec(180,overshoot)))) is SpecResult.Accepted)
    }

}
