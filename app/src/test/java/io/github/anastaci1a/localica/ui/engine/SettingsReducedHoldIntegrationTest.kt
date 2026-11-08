package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** Apply with the reviewed Stage3 SettingsSearchMotion owner, not to Stage2 alone. */
class SettingsReducedHoldIntegrationTest {
    private fun EngineHarness.access(access: MotionAccess) =
        clock.configure(EngineContext(1f / 120, 1000f, access), StockUiSpec)
    @Test fun bornReducedHoldExpiresAt1200AndRetiresOnlyAtObservedFinalRest() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val highlight = SettingsHighlightMotion(h.clock)
                highlight.request(true, true)
                val opacity = (StockUiSpec.motion.getValue(MotionProfile.SettingsHighlight) as SettingsHighlightSpec).opacity
                assertEquals(opacity, highlight.value(), 0f)
                assertEquals(1, h.clock.activeCount)
                h.advance(1.199)
                assertEquals(opacity, highlight.value(), 0f)
                highlight.dispose() // The detached pulse remains registered to its final Rest.
                assertEquals(1, h.clock.activeCount)
                assertEquals(1, observer.continuity.registeredCount)
                h.advance(.001)
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                assertTrue(evidence.violations.toString(), evidence.violations.isEmpty())
            }
        }
    }
    @Test fun laterNormalToReducedEndsHoldAndCancellationCannotRetireOrWriteItsSuccessor() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val highlight = SettingsHighlightMotion(h.clock)
                highlight.request(true, true)
                h.advance(.2); h.access(MotionAccess.Standard)
                h.advance(.2)
                assertEquals(.18f, highlight.value(), 0f)
                highlight.request(false, false)
                highlight.request(true, false)
                assertEquals(2, h.clock.activeCount)
                h.advance(.15)
                assertTrue(highlight.value() > 0f)
                h.access(MotionAccess.Reduced)
                assertEquals(0f, highlight.value(), 0f)
                assertEquals(0, h.clock.activeCount)
                highlight.dispose()
                assertEquals(0, observer.continuity.registeredCount)
                assertTrue(evidence.violations.toString(), evidence.violations.isEmpty())
            }
        }
    }
    @Test fun liveReducedHoldStillFollowsTheNextNormalToReducedTransition() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val highlight = SettingsHighlightMotion(h.clock)
                highlight.request(true, true)
                h.advance(.2); h.access(MotionAccess.Standard); h.advance(.2)
                assertEquals(.18f, highlight.value(), 0f)
                assertEquals(1, h.clock.activeCount)
                h.access(MotionAccess.Reduced)
                assertEquals(0f, highlight.value(), 0f)
                assertTrue(highlight.settled)
                assertEquals(0, h.clock.activeCount)
                highlight.dispose()
                assertEquals(0, observer.continuity.registeredCount)
                assertTrue(evidence.violations.toString(), evidence.violations.isEmpty())
            }
        }
    }
    @Test fun ordinaryProfilesAndSpringsStillFollowRootReduction() {
        EngineHarness().use { h ->
            val ordinary = MotionFloat(h.clock, 0f, 1f)
            val spring = MotionFloat(h.clock, 0f, 1f)
            ordinary.retarget(1f, motionProfileId(MotionProfile.Control))
            spring.retargetSpring(1f, springProfileId(SpringProfile.Page))
            h.advance(.02); h.access(MotionAccess.Reduced)
            assertEquals(1f, ordinary.value(), 0f); assertEquals(1f, spring.value(), 0f)
            assertEquals(0, h.clock.activeCount)
            ordinary.dispose(); spring.dispose()
        }
    }
}
