package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** Installs the REAL observation hook; every active highlight id must be registered and balanced. */
class SettingsHighlightObservationTest {
    @Test fun everyHighlightTickAndFinalRestAreObservedInAllAccessModes() {
        for (access in MotionAccess.entries) EngineHarness(access).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val owner = SettingsHighlightMotion(h.clock)
                owner.request(true, access == MotionAccess.Reduced)
                assertEquals(1, observer.continuity.registeredCount)
                repeat(125) {
                    h.advance(.01)
                    assertTrue(owner.value() in 0f.. .18f)
                }
                assertTrue(owner.settled)
                assertEquals(0f, owner.value(), 0f)
                owner.dispose()
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                assertEquals("access=$access", emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun cancelledAndDisposedViewsHideImmediatelyButRetireTheirObservedPulseAtRest() {
        for (access in MotionAccess.entries) EngineHarness(access).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val owner = SettingsHighlightMotion(h.clock)
                owner.request(true, access == MotionAccess.Reduced)
                h.advance(.075)
                assertTrue(owner.value() > 0f)
                owner.request(false, access == MotionAccess.Reduced)
                assertEquals(0f, owner.value(), 0f)
                assertEquals(1, observer.continuity.registeredCount)
                assertEquals(1, h.clock.activeCount)
                h.advance(1.2)
                assertEquals(0, observer.continuity.registeredCount)
                owner.request(true, access == MotionAccess.Reduced)
                h.advance(.075)
                owner.value()
                owner.dispose()
                assertEquals(0f, owner.value(), 0f)
                assertEquals(1, observer.continuity.registeredCount)
                h.advance(1.2)
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                assertEquals("access=$access", emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun ordinaryEasedAndSpringOwnersKeepTheirContinuousCompletionAndBalancedRetirement() {
        assertEquals("Continuous", Eased(0.0, 0.0, 1.0, EasingSpec(.4f,0f,.2f,1f), .2).completionPolicy)
        assertEquals("Continuous", Damped(0.0, 0.0, 1.0, 10.0, 1.0, 1.0, 1.0/120).completionPolicy)
        for (access in listOf(MotionAccess.Standard, MotionAccess.Faster)) EngineHarness(access).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val joins = mutableListOf<io.github.anastaci1a.localica.ui.engine.MotionJoin>()
                h.clock.events = object : MotionEvents by observer {
                    override fun joined(motion: MotionFloat, join: io.github.anastaci1a.localica.ui.engine.MotionJoin) {
                        joins += join
                        observer.joined(motion, join)
                    }
                }
                val eased = MotionFloat(h.clock, 0f, 1f)
                val spring = MotionFloat(h.clock, 0f, 1f)
                eased.retarget(1f, motionProfileId(MotionProfile.Control))
                spring.retargetSpring(1f, springProfileId(SpringProfile.GlassMode))
                eased.value(); spring.value()
                h.advance(3.0)
                assertTrue(eased.settled && spring.settled)
                val completion = joins.filter { it.afterKind == 0 && it.beforeKind in 1..2 }
                assertEquals(2, completion.size)
                assertTrue(completion.all { it.policy == "Continuous" })
                eased.dispose(); spring.dispose()
                assertEquals(0, observer.continuity.registeredCount)
                assertEquals(0, h.clock.activeCount)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }
}
