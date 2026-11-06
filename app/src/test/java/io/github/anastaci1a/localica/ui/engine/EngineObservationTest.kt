package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class EngineObservationTest {
    @Test fun actualEngineJoinsFramesAndRetirementsAreObservedWithoutDrivingOwners() {
        EngineHarness().use { h ->
            val evidence=BoundedEvidence(100)
            EngineObservation(h.clock,evidence).use { observer ->
                val m=MotionFloat(h.clock,0f,1f); val profile=motionProfileId(MotionProfile.Control)
                m.initialize(.1f); m.initialize(.2f) // Permitted before first actual presentation.
                m.value(); m.retarget(1f,profile)
                h.now+=1_000_000; m.retarget(.7f,profile) // Same frame sequence, fresh intent time.
                h.advance(.01); m.value()
                m.drive(.5f,h.clock.stampNow); h.now+=10_000_000; m.drive(.6f,h.clock.stampNow)
                m.release(1f,profile,10f)
                h.advance(.5); assertTrue(m.settled); m.dispose()
                assertEquals(0,observer.continuity.registeredCount)
                assertEquals(emptyList<Violation>(),evidence.violations)
            }
            assertNull(h.clock.events); assertTrue(h.clock.observers.isEmpty())
        }
    }
    @Test fun actualCurveFactoriesRegisterTheirAnalyticJoins() {
        EngineCurveObservation().use { observer ->
            val window=Curve.window(.2f,.8f); val soft=Curve.softWindow(.1f,.9f)
            Curve.linear(10f,20f).compose(window); soft*window; window+soft; window.mix(soft,Curve.window(0f,1f))
            assertEquals(emptyList<Violation>(),observer.registry.verify(600108,1000))
        }
        assertNull(CurveHooks.created)
    }

}
