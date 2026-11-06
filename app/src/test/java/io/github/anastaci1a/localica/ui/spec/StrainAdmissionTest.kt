package io.github.anastaci1a.localica.ui.spec

import io.github.anastaci1a.localica.ui.GlassMomentum
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

/** A2.2c: published spans normalize tolerances; they NEVER bound or clip raw spring states.
 * Motion/depth tanh maps have derivative sech²<=1, so raw settlement implies visible settlement.
 * Pressure's .34 is its target span [-.22,.12], not a claim that a release cannot overshoot. */
class StrainAdmissionTest {
    private fun state(body: GlassMomentum, channel: String): Pair<Double,Double> {
        val spring=body.javaClass.getDeclaredField(channel).apply { isAccessible=true }.get(body)
        fun get(field: String)=spring.javaClass.getDeclaredField(field).apply { isAccessible=true }.getFloat(spring).toDouble()
        return get("value") to get("velocity")
    }
    @Test fun actualReachableChannelsHaveFinitePhysicalHorizonsForEverySupportedDamping() {
        val r=Random(600000)
        val spans=mapOf("motion" to StrainMotionSpan,"pressure" to StrainPressureSpan,"depth" to StrainDepthSpan)
        assertEquals(.24f,StrainMotionSpan,0f); assertEquals(.34f,StrainPressureSpan,0f); assertEquals(.48f,StrainDepthSpan,0f)
        for(damping in 2..40) for(speed in listOf(4.0/3,2.0)) {
            val body=GlassMomentum(damping.toFloat())
            val profile=SpringProfileSpec((damping/(2*sqrt(380.0))).toFloat(),380f,2)
            // Long held pressure/contact establishes the endpoints; then nontrivial motion/contact sequences.
            repeat(360) { body.advance(0f,(speed/120).toFloat(),-.22f,1f) }
            repeat(100) {
                for((channel,span) in spans) {
                    val (x,v)=state(body,channel)
                    val physicalV=v*speed
                    val t=springHorizon(profile,x,physicalV,span.toDouble(),speed)
                    assertTrue("damping$damping speed$speed $channel $x $v Ts$t",t.isFinite() && t>0)
                    val w=sqrt(380.0)*speed; val a=damping*speed/2; val wd=sqrt(w*w-a*a)
                    if(wd.isFinite()) {
                        val c=hypot(x,(physicalV+a*x)/wd)
                        assertTrue(c*exp(-a*t)<=SettleThreshold*span*1.00001)
                        assertTrue(c*w*exp(-a*t)<=SettleVelocityThreshold*span*1.00001)
                        assertTrue(minOf(t/8,.064,1/a)*a<=1.00000001)
                    }
                }
                body.advance(r.nextFloat()*40-20,(speed/120).toFloat(),if(r.nextBoolean()) -.22f else 0f,if(r.nextBoolean()) 1f else 0f)
            }
        }
    }
    @Test fun pressureDampingTwoIsPreservedWithoutAnyTwoSecondSnap() {
        val body=GlassMomentum(2f)
        repeat(360) { body.advance(0f,1f/120,-.22f,1f) }
        val(x,v)=state(body,"pressure")
        assertEquals(-.22,x,.000001); assertEquals(0.0,v,0.0)
        val spec=SpringProfileSpec((1/sqrt(380.0)).toFloat(),380f,2)
        val horizon=springHorizon(spec,x,v,StrainPressureSpan.toDouble(),4.0/3)
        assertTrue(horizon>2)
        repeat(240) { body.advance(0f,1f/120) }
        assertFalse("Existing low damping is deliberately still active after two integration seconds",body.settled)
    }
    @Test fun timeScalingPreservesEachExistingIntegratorSubstepExactly() {
        val r=Random(600001)
        for(speed in listOf(4.0/3,2.0)) repeat(10000) {
            val x=r.nextDouble()*.6-.3; val v=r.nextDouble()*4-2; val q=r.nextDouble()*.34-.22
            val damping=2+r.nextDouble()*38; val physicalDt=r.nextDouble()/(240*speed)
            val profileDt=physicalDt*speed
            val profileNextV=v+(380*(q-x)-damping*v)*profileDt
            val physicalV=v*speed
            val physicalNextV=physicalV+(380*speed*speed*(q-x)-damping*speed*physicalV)*physicalDt
            assertEquals(profileNextV*speed,physicalNextV,1e-12)
            assertEquals(x+profileNextV*profileDt,x+physicalNextV*physicalDt,1e-12)
        }
    }
}
