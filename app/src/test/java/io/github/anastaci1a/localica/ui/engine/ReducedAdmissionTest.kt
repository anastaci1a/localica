package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class ReducedAdmissionTest {
    private fun EngineHarness.access(access: MotionAccess) =
        clock.configure(EngineContext(1f / 120, 1000f, access), StockUiSpec)

    @Test fun callbackCreatedCustomAndDrivenRootWorkAdmitRestAndRetireThroughActualObserver() {
        EngineHarness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val first = MotionFloat(h.clock, 0f, 1f)
                first.retarget(1f, motionProfileId(MotionProfile.Control))
                val created = mutableListOf<MotionFloat>()
                first.onRest = {
                    val custom = MotionFloat(h.clock, 0f, 1f)
                    created += custom
                    custom.commit(custom.prepareSegment(Eased(0.0, 0.0, 1.0,
                        EasingSpec(.4f, 0f, .2f, 1f), .1), 1f, h.clock.stampNow))
                    assertTrue(custom.settled)
                    val input = MotionFloat(h.clock, 0f, 1f)
                    created += input
                    input.drive(.4f, h.clock.stampNow)
                    assertEquals(.4f, input.value(), 0f)
                    assertEquals(.4f, input.target, 0f)
                    assertTrue(input.settled)
                    input.release(1f, motionProfileId(MotionProfile.Control), 2f)
                    assertEquals(1f, input.value(), 0f)
                }
                h.access(MotionAccess.Reduced)
                assertEquals(0, h.clock.activeCount)
                first.dispose(); created.forEach { it.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertTrue(evidence.violations.toString(), evidence.violations.isEmpty())
            }
        }
    }

    @Test fun reducedReleaseRequiresOneAcceptedDriveAndPreservesEvidenceAcrossReduction() {
        EngineHarness().use { h ->
            val m = MotionFloat(h.clock, 0f, 1f)
            val profile = motionProfileId(MotionProfile.Control)
            h.access(MotionAccess.Reduced)
            assertThrows(IllegalStateException::class.java) { m.release(1f, profile, 0f) }
            m.drive(.4f, h.clock.stampNow)
            m.release(1f, profile, 0f)
            assertThrows(IllegalStateException::class.java) { m.release(0f, profile, 0f) }
            h.access(MotionAccess.Standard)
            m.drive(.3f, h.clock.stampNow)
            h.access(MotionAccess.Reduced)
            m.release(.8f, profile, 0f)
            assertEquals(.8f, m.value(), 0f)
            assertThrows(IllegalStateException::class.java) { m.release(0f, profile, 0f) }
            m.dispose()
        }
    }

    @Test fun reducedDriveRestCallbackCanReleaseOrDisposeWithoutLateWrites() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val profile = motionProfileId(MotionProfile.Control)
                val m = MotionFloat(h.clock, 0f, 1f)
                var callbacks = 0
                m.onRest = {
                    callbacks++
                    if (callbacks == 1) m.release(1f, profile, 0f)
                }
                m.drive(.4f, h.clock.stampNow)
                assertEquals(2, callbacks)
                assertEquals(1f, m.value(), 0f)
                assertThrows(IllegalStateException::class.java) { m.release(0f, profile, 0f) }
                m.onRest = null; m.dispose()
                val disposed = MotionFloat(h.clock, 0f, 1f)
                disposed.onRest = { disposed.dispose() }
                disposed.drive(.5f, h.clock.stampNow)
                assertThrows(IllegalStateException::class.java) { disposed.drive(.6f, h.clock.stampNow) }
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                assertTrue(evidence.violations.toString(), evidence.violations.isEmpty())
            }
        }
    }

}
