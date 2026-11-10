package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import org.junit.Assert.*
import org.junit.Test

class DepartingHomeHoldTest {
    private val basis = ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(30f, 50f, 800f, 900f), 1f)

    @Test fun holdKeepsTheSamePhaseAndFourEdgesDespiteFramesTargetsAndMeasurement() = EngineHarness().use { h ->
        val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
        owner.setBasis(basis)
        val rect = MotionRect(h.clock, basis.startBounds, EdgeRanges(2000f, 2000f, 2000f, 2000f))
        owner.bind(rect)
        owner.target(true)
        h.advance(.08)
        val phase = owner.phase.valueAt(h.clock.stampNow)
        val edges = rect.valueAt(h.clock.stampNow)
        owner.hold()
        owner.target(false)
        val changed = basis.copy(endBounds = Rect(40f, 60f, 850f, 950f))
        owner.setBasis(changed)
        repeat(12) {
            h.advance(.04)
            owner.target(false)
            owner.hold()
            assertEquals(phase, owner.phase.valueAt(h.clock.stampNow), 0f)
            assertEquals(edges, rect.valueAt(h.clock.stampNow))
            assertFalse(owner.awaitingNativeGate)
            assertFalse(owner.retirementReady)
        }
        assertFalse(owner.expanded)
        owner.release()
        assertEquals(phase, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertEquals(edges, rect.valueAt(h.clock.stampNow))
        h.advance(2.0)
        assertEquals(0f, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertEquals(changed.startBounds, rect.valueAt(h.clock.stampNow))
        assertTrue(owner.retirementReady)
        assertNull(owner.pendingBasis)
        owner.dispose()
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun holdAndContactAreMutuallyExclusiveAndDisposalRetiresHeldChannels() = EngineHarness().use { h ->
        val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
        owner.setBasis(basis)
        owner.contactBegin(h.clock.stampNow)
        assertThrows(IllegalStateException::class.java) { owner.hold() }
        owner.contactCancel()
        owner.hold()
        assertFalse(owner.canContact)
        assertThrows(IllegalStateException::class.java) { owner.contactBegin(h.clock.stampNow) }
        owner.dispose()
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun liveReductionDrainsHeldWorkWithoutMovingItOrAdmittingRetirement() = EngineHarness().use { h ->
        val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
        owner.setBasis(basis)
        val rect = MotionRect(h.clock, basis.startBounds, EdgeRanges(2000f, 2000f, 2000f, 2000f))
        owner.bind(rect)
        owner.target(true)
        h.advance(.08)
        owner.hold()
        owner.target(false)
        val phase = owner.phase.valueAt(h.clock.stampNow)
        val edges = rect.valueAt(h.clock.stampNow)
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
        assertEquals(0, h.clock.activeCount)
        assertEquals(phase, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertEquals(edges, rect.valueAt(h.clock.stampNow))
        assertFalse(owner.awaitingNativeGate)
        assertFalse(owner.retirementReady)
        owner.release()
        assertEquals(0f, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertTrue(owner.retirementReady)
        owner.dispose()
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun nativeReceiptCannotRetireTheHeldFlightAndReleaseResumesItsLogicalReturn() {
        val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 3000f, 1f / 60)) as SpecResult.Accepted).spec
        EngineHarness(spec = spec).use { h ->
            val popup = PopupWindowMotion(h.clock, spec.expansion, "departing-home", false, false)
            popup.show(true)
            popup.place(basis.startBounds, basis.endBounds, 1f)
            assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration))
            h.advance(2.0)
            popup.show(false)
            h.advance(.032)
            popup.hold()
            val phase = popup.expansion.phase.valueAt(h.clock.stampNow)
            val edges = popup.bounds!!.valueAt(h.clock.stampNow)
            val generation = popup.expansion.nativeGeneration
            repeat(8) {
                popup.show(false)
                popup.place(basis.startBounds, basis.endBounds, 1f)
                h.advance(.05)
                assertEquals(phase, popup.expansion.phase.valueAt(h.clock.stampNow), 0f)
                assertEquals(edges, popup.bounds!!.valueAt(h.clock.stampNow))
                assertFalse(popup.expansion.acknowledgeNativeGate(generation))
                assertFalse(popup.expansion.retirementReady)
            }
            popup.release()
            h.advance(2.0)
            assertTrue(popup.expansion.awaitingNativeGate)
            assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration))
            assertTrue(popup.expansion.retirementReady)
            popup.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun reopeningHeldFlightReusesTheOwnerAndItsPhysicalRectangle() = EngineHarness().use { h ->
        val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
        owner.setBasis(basis)
        val rect = MotionRect(h.clock, basis.startBounds, EdgeRanges(2000f, 2000f, 2000f, 2000f))
        owner.bind(rect)
        owner.target(true)
        h.advance(.08)
        owner.hold()
        owner.target(false)
        owner.target(true)
        val phase = owner.phase.valueAt(h.clock.stampNow)
        val edges = rect.valueAt(h.clock.stampNow)
        owner.release()
        assertTrue(owner.owns(rect))
        assertEquals(phase, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertEquals(edges, rect.valueAt(h.clock.stampNow))
        h.advance(2.0)
        assertEquals(1f, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertEquals(basis.endBounds, rect.valueAt(h.clock.stampNow))
        owner.dispose()
        assertEquals(0, h.clock.activeCount)
    }
}
