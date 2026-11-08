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
}
