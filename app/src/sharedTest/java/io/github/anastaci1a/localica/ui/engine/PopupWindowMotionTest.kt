package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class PopupWindowMotionTest {
    private val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
    private val home = Rect(40f, 50f, 88f, 98f)
    private val envelope = Rect(10f, 20f, 310f, 620f)

    @Test fun ordinaryWindowWaitsAtHomeForItsNativeReceiptAndReusesItsMeasuredEdges() = EngineHarness(spec = spec).use { h ->
        val motion = PopupWindowMotion(h.clock, spec.expansion, "popup", prepared = false, quiet = false)
        motion.show(true)
        assertNull(motion.bounds)
        motion.place(home, envelope, 1f)
        val rect = requireNotNull(motion.bounds)
        assertTrue(motion.expansion.awaitingNativeGate)
        assertEquals(home, rect.valueAt(h.clock.stampNow))
        assertEquals(0, h.clock.activeCount)
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
        h.advance(.08)
        val before = rect.left.sampleAt(h.clock.stampNow)
        motion.place(home, envelope.copy(right = 380f, bottom = 500f), 1f)
        assertSame(rect, motion.bounds)
        assertEquals(before.value, rect.left.valueAt(h.clock.stampNow), 1e-3f)
        assertEquals(before.velocity, rect.left.velocityAt(h.clock.stampNow), 1e-3f)
        h.advance(2.0)
        motion.show(false); h.advance(2.0)
        assertTrue(motion.expansion.awaitingNativeGate)
        assertEquals(home, rect.valueAt(h.clock.stampNow))
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
        assertTrue(motion.expansion.retirementReady)
        motion.dispose()
    }

    @Test fun preparedOriginAndPopupShareOnePhaseAcrossBothReceiptGates() = EngineHarness(spec = spec).use { h ->
        val motion = PopupWindowMotion(h.clock, spec.expansion, "lyrics", prepared = true, quiet = false)
        motion.show(true); motion.place(home, envelope, 1f); h.advance(2.0)
        val e = motion.expansion
        assertTrue(e.awaitingNativeGate)
        val q = e.phase.valueAt(h.clock.stampNow)
        assertEquals(.25f, q, 0f)
        assertTrue(motion.preparationCurve.at(q) > .5f)
        assertEquals(0f, motion.popupCurve.at(q), 0f)
        assertEquals(home, requireNotNull(motion.bounds).valueAt(h.clock.stampNow))
        e.acknowledgeNativeGate(e.nativeGeneration); h.advance(2.0)
        assertEquals(envelope, requireNotNull(motion.bounds).valueAt(h.clock.stampNow))
        motion.show(false); h.advance(2.0)
        assertTrue(e.awaitingNativeGate)
        assertEquals(.25f, e.phase.valueAt(h.clock.stampNow), 0f)
        e.acknowledgeNativeGate(e.nativeGeneration); h.advance(2.0)
        assertTrue(e.retirementReady)
        assertEquals(0f, motion.preparationCurve.at(e.phase.valueAt(h.clock.stampNow)), 0f)
        motion.dispose()
    }
    @Test fun freshWindowSeedsItsMeasuredHomeAfterThePreviousWindowRetired() = EngineHarness(spec = spec).use { h ->
        val motion = PopupWindowMotion(h.clock, spec.expansion, "fresh-window", false, false)
        val originalHost = ShadowPlane(h.clock, HostKey.next(Any()))
        motion.attached(Any(), originalHost); motion.show(true); motion.place(home, envelope, 1f)
        val rect = requireNotNull(motion.bounds)
        val phase = motion.expansion.phase
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration); h.advance(2.0)
        motion.show(false); h.advance(2.0)
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
        assertTrue(motion.expansion.retirementReady)
        val measured = Rect(70f, 90f, 166f, 186f)
        val freshHost = ShadowPlane(h.clock, HostKey.next(Any()))
        motion.show(true)
        val generation = motion.expansion.nativeGeneration
        motion.attached(Any(), freshHost); motion.place(measured, envelope, 1f)
        assertSame("The phase identity survives the host change", phase, motion.expansion.phase)
        assertEquals("Measurement cannot replace the intent generation", generation, motion.expansion.nativeGeneration)
        assertEquals("The new host has never presented", 0L, freshHost.generation)
        assertSame("One expansion keeps its bound rectangle", rect, motion.bounds)
        assertEquals("The first new window cannot expose the retired window's home", measured, rect.valueAt(h.clock.stampNow))
        assertTrue(motion.expansion.awaitingNativeGate)
        assertEquals(0f, motion.expansion.phase.valueAt(h.clock.stampNow), 0f)
        listOf(rect.left, rect.top, rect.right, rect.bottom).forEach { assertEquals(0f, it.velocityAt(h.clock.stampNow), 0f) }
        motion.dispose()
    }

    @Test fun replacingAWindowDuringAFlightPreservesItsPresentedKinematics() = EngineHarness(spec = spec).use { h ->
        val motion = PopupWindowMotion(h.clock, spec.expansion, "live-window", false, false)
        val initialHost = ShadowPlane(h.clock, HostKey.next(Any()))
        motion.attached(Any(), initialHost); motion.show(true); motion.place(home, envelope, 1f)
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration); h.advance(.06)
        val rect = requireNotNull(motion.bounds)
        val before = listOf(rect.left, rect.top, rect.right, rect.bottom).map { it.sampleAt(h.clock.stampNow) }
        val replacementHost = ShadowPlane(h.clock, HostKey.next(Any()))
        motion.attached(Any(), replacementHost); motion.place(home.translate(20f, 10f), envelope, 1f)
        assertEquals(0L, replacementHost.generation)
        listOf(rect.left, rect.top, rect.right, rect.bottom).forEachIndexed { index, edge ->
            assertEquals(before[index].value, edge.valueAt(h.clock.stampNow), 1e-3f)
            assertEquals(before[index].velocity, edge.velocityAt(h.clock.stampNow), 1e-3f)
        }
        motion.dispose()
    }

    @Test fun aPresentedHostCannotReinitializeTheSourceRectangle() = EngineHarness(spec = spec).use { h ->
        val motion = PopupWindowMotion(h.clock, spec.expansion, "presented-host", false, false)
        motion.show(true); motion.place(home, envelope, 1f)
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration); h.advance(2.0)
        motion.show(false); h.advance(2.0)
        motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
        val rect = requireNotNull(motion.bounds)
        val before = listOf(rect.left, rect.top, rect.right, rect.bottom).map { it.sampleAt(h.clock.stampNow) }
        val presented = ShadowPlane(h.clock, HostKey.next(Any()))
        presented.receipt()
        assertTrue(presented.generation > 0L)
        motion.show(true); motion.attached(Any(), presented); motion.place(home.translate(20f, 10f), envelope, 1f)
        listOf(rect.left, rect.top, rect.right, rect.bottom).forEachIndexed { index, edge ->
            assertEquals(before[index].value, edge.valueAt(h.clock.stampNow), 1e-3f)
            assertEquals(before[index].velocity, edge.velocityAt(h.clock.stampNow), 1e-3f)
        }
        motion.dispose()
    }

}
