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
    @Test fun publishedAdmissionSpansRemainExact() {
        assertEquals(.24f, StrainMotionSpan, 0f)
        assertEquals(.34f, StrainPressureSpan, 0f)
        assertEquals(.48f, StrainDepthSpan, 0f)
    }
    @Test fun pressureDampingTwoPreservesTheActualBodyRelease() {
        val body=GlassMomentum(2f)
        repeat(360) { body.advance(0f,1f/120,-.22f,1f) }
        assertTrue("Normal held convergence uses the owning clock completion", body.finishSettlement())
        val(x,v)=state(body,"pressure")
        assertEquals(-.22,x,.000001); assertEquals(0.0,v,0.0)
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
