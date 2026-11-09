package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class ToastMotionTest {
    private fun harness(access: MotionAccess = MotionAccess.Standard): EngineHarness {
        val settings = InterfaceSettings(reducedMotion = access == MotionAccess.Reduced)
        val spec = (uiSpec(settings, geometry = ExpansionGeometryDomain(1000f, 2400f, 1f / 60)) as SpecResult.Accepted).spec
        return EngineHarness(access, spec)
    }

    @Test fun exitBeforeFirstEntryRetiresExactlyOnceWithoutStartingFrames() {
        for (access in listOf(MotionAccess.Standard, MotionAccess.Reduced)) harness(access).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val endpoints = mutableListOf<ToastMotionEndpoint>()
                val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
                try {
                    motion.exit()
                    assertEquals(listOf(ToastMotionEndpoint.Exited), endpoints)
                    assertEquals(-400f, motion.leftPx, 0f)
                    assertEquals(0, h.clock.activeCount)
                    assertEquals(0, h.awaits)
                    motion.exit()
                    assertEquals(1, endpoints.size)
                } finally { motion.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun oneCoordinateSurvivesHoldReversalReleaseAndFiniteExit() {
        harness().use { h ->
            val endpoints = mutableListOf<ToastMotionEndpoint>()
            val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
            assertEquals(-400f, motion.leftPx, 0f)
            motion.enter(); h.advance(1.0)
            assertEquals(12f, motion.leftPx, 0f)
            assertEquals(listOf(ToastMotionEndpoint.Visible), endpoints)
            assertTrue(motion.contact())
            h.now += 10_000_000
            motion.dragBy(-120f)
            assertEquals(-108f, motion.leftPx, 0f)
            h.now += 10_000_000
            motion.dragBy(40f)
            assertEquals(-68f, motion.leftPx, 0f)
            motion.release(false, 100f)
            assertEquals(-68f, motion.leftPx, 0f)
            h.advance(2.0)
            assertEquals(12f, motion.leftPx, 0f)
            assertEquals(listOf(ToastMotionEndpoint.Visible, ToastMotionEndpoint.Visible), endpoints)
            motion.exit()
            assertEquals(12f, motion.leftPx, 0f)
            h.advance(2.0)
            assertEquals(-400f, motion.leftPx, 0f)
            assertEquals(ToastMotionEndpoint.Exited, endpoints.last())
            val awaits = h.awaits
            h.advance(1.0)
            assertEquals(awaits, h.awaits)
            motion.dispose()
        }
    }

    @Test fun committedReleaseKeepsItsJoinAndCannotBeGrabbedAgain() {
        harness().use { h ->
            val endpoints = mutableListOf<ToastMotionEndpoint>()
            val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
            motion.enter(); h.advance(1.0)
            motion.contact(); h.now += 10_000_000; motion.dragBy(-120f)
            val left = motion.leftPx
            motion.release(true, -600f)
            assertEquals(left, motion.leftPx, 0f)
            assertFalse(motion.contact())
            h.advance(2.0)
            assertEquals(-400f, motion.leftPx, 0f)
            assertEquals(ToastMotionEndpoint.Exited, endpoints.last())
            motion.dispose()
        }
    }

    @Test fun geometryRetargetKeepsActualXAndReducedUsesExistingRootPolicy() {
        harness().use { h ->
            val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f) {}
            motion.enter(); h.advance(.08)
            val left = motion.leftPx
            motion.place(300f, 16f)
            assertEquals(left, motion.leftPx, 0f)
            h.advance(2.0)
            assertEquals(16f, motion.leftPx, 0f)
            motion.dispose()
        }
        harness(MotionAccess.Reduced).use { h ->
            val endpoints = mutableListOf<ToastMotionEndpoint>()
            val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
            motion.enter()
            assertEquals(12f, motion.leftPx, 0f)
            assertEquals(listOf(ToastMotionEndpoint.Visible), endpoints)
            motion.exit()
            assertEquals(-400f, motion.leftPx, 0f)
            assertEquals(ToastMotionEndpoint.Exited, endpoints.last())
            motion.dispose()
        }
    }

    @Test fun reducedMotionEnabledDuringContactStillCompletesEitherRelease() {
        for (dismiss in listOf(false, true)) harness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val endpoints = mutableListOf<ToastMotionEndpoint>()
                val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
                try {
                    motion.enter(); h.advance(.08)
                    assertTrue(motion.contact())
                    h.now += 10_000_000; motion.dragBy(-40f)
                    h.clock.configure(requireNotNull(h.clock.context).copy(access = MotionAccess.Reduced), h.clock.spec)
                    assertTrue(endpoints.isEmpty())
                    motion.release(dismiss, -300f)
                    assertEquals(if (dismiss) -400f else 12f, motion.leftPx, 0f)
                    assertEquals(listOf(if (dismiss) ToastMotionEndpoint.Exited else ToastMotionEndpoint.Visible), endpoints)
                    assertEquals(0, h.clock.activeCount)
                } finally { motion.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun realObserverSeesEntryDragGeometryRetargetExitAndDisposal() {
        harness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val endpoints = mutableListOf<ToastMotionEndpoint>()
                val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f, endpoints::add)
                motion.enter(); h.advance(.08); motion.leftPx
                motion.contact(); h.now += 10_000_000; motion.dragBy(-40f)
                motion.place(300f, 16f)
                h.now += 10_000_000; motion.dragBy(20f)
                // The final drive moves 20px in 10ms; supply its actual release velocity.
                motion.release(false, 2000f); h.advance(1.0)
                assertEquals(16f, motion.leftPx, 0f)
                motion.exit(); h.advance(.08); motion.leftPx
                motion.place(320f, 20f); h.advance(1.0)
                assertEquals(-344f, motion.leftPx, 0f)
                val calls = endpoints.toList()
                motion.dispose()
                assertEquals(0, h.clock.activeCount)
                assertEquals(0, observer.continuity.registeredCount)
                h.advance(.5)
                assertEquals(calls, endpoints)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun liveShadowClearanceRetargetsSameOwnerAndKeepsCurrentCoordinate() {
        harness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val motion = ToastMotion(h.clock, "toast", 376f, 12f, 24f) {}
                motion.enter(); h.advance(.08)
                val before = motion.leftPx
                motion.place(376f, 12f, 64f)
                assertEquals(before, motion.leftPx, 0f)
                assertEquals(1, observer.continuity.registeredCount)
                h.advance(1.0); motion.exit(); h.advance(.08)
                val leaving = motion.leftPx
                motion.place(300f, 20f, 96f)
                assertEquals(leaving, motion.leftPx, 0f)
                h.advance(1.0)
                assertEquals(-396f, motion.leftPx, 0f)
                motion.dispose()
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun buttonsDepartOnlyAsCurrentExtentReachesEachOriginalRectangle() {
        val buttons = listOf(Rect(20f, 0f, 70f, 50f), Rect(175f, 0f, 225f, 50f), Rect(330f, 0f, 380f, 50f))
        fun frame(right: Float) = buttons.map { toastButtonProjection(Rect(right - 376f, 0f, right, 50f), it, 20f) }
        assertEquals(listOf(1f, 1f, 1f), frame(20f).map { it.alpha })
        assertEquals(listOf(.5f, 1f, 1f), frame(45f).map { it.alpha })
        assertEquals(listOf(0f, .5f, 1f), frame(200f).map { it.alpha })
        assertEquals(listOf(0f, 0f, .5f), frame(355f).map { it.alpha })
        assertEquals(listOf(10f, 0f, 0f), frame(45f).map { it.offsetY })
        assertFalse(frame(45f).first().uncovered)
        assertTrue(frame(45f).last().uncovered)
    }

    @Test fun equalExtentHasEqualProjectionAcrossEntryExitAndBackAndForthDrag() {
        val button = Rect(100f, 0f, 150f, 50f)
        val expected = toastButtonProjection(Rect(-251f, 0f, 125f, 50f), button, 20f)
        // The same extent recurs after different movement histories and at different times.
        for (path in listOf(listOf(-10f, 125f), listOf(388f, 125f), listOf(388f, 90f, 160f, 125f))) {
            val frames = path.map { right -> toastButtonProjection(Rect(right - 376f, 0f, right, 50f), button, 20f) }
            assertEquals(expected, frames.last())
        }
    }

    @Test fun releasePastRestingLeftUsesFiniteOverlapAndCannotHideAnUncoveredButton() {
        val button = Rect(20f, 0f, 70f, 50f)
        assertEquals(1f, toastButtonProjection(Rect(80f, 0f, 456f, 50f), button, 20f).alpha)
        assertEquals(.5f, toastButtonProjection(Rect(45f, 0f, 421f, 50f), button, 20f).alpha)
        assertEquals(0f, toastButtonProjection(Rect(12f, 0f, 388f, 50f), button, 20f).alpha)
    }

    @Test fun projectionDoesNotDependOnRaisedRowOrPriorDownwardDisplacement() {
        val a = toastButtonProjection(Rect(-251f, 650f, 125f, 700f), Rect(100f, 650f, 150f, 700f), 20f)
        val b = toastButtonProjection(Rect(-251f, 450f, 125f, 500f), Rect(100f, 450f, 150f, 500f), 20f)
        assertEquals(a, b)
    }
}
