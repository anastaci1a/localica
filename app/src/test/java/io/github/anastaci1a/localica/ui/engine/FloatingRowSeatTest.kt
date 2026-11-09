package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class FloatingRowSeatTest {
    @Test fun offscreenQueueCannotTakeSeatAndReversalUsesIdenticalGeometry() {
        val queue = listOf(1400f, 1100f, 800f, 760f, 700f)
        val forward = queue.map { floatingRowSeat(600f, 760f, 1f, it) }
        assertEquals(listOf(760f, 760f, 760f, 760f, 700f), forward)
        assertEquals(forward.reversed(), queue.reversed().map { floatingRowSeat(600f, 760f, 1f, it) })
    }

    @Test fun playerProjectionAndRaisedQueueGeometryFollowActualOwners() {
        assertEquals(600f, floatingRowSeat(600f, 760f, 0f, null), 0f)
        assertEquals(680f, floatingRowSeat(600f, 760f, .5f, 1000f), 0f)
        assertEquals(650f, floatingRowSeat(600f, 760f, .5f, 650f), 0f)
        assertEquals(600f, floatingRowSeat(600f, 760f, 1f, 600f), 0f)
        // Rotation/inset/selection changes reproject immediately, with no stale admission latch.
        assertEquals(420f, floatingRowSeat(300f, 460f, 1f, 420f), 0f)
        assertEquals(460f, floatingRowSeat(300f, 460f, 1f, 700f), 0f)
    }
    @Test fun realPlayerOwnerFeedsProjectionWithoutAnotherMotionOrAdmissionHistory() {
        for (access in listOf(MotionAccess.Standard, MotionAccess.Reduced)) EngineHarness(access).use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                val player = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
                player.setBasis(ExpansionBasis(Rect(0f, 600f, 400f, 650f), Rect(0f, 0f, 400f, 800f), 1f))
                val queue = mutableFloatStateOf(1400f)
                val browse = mutableFloatStateOf(600f)
                val full = mutableFloatStateOf(760f)
                val seat = FloatingRowSeatProjection({ browse.floatValue }, { full.floatValue },
                    { player.phase.value() }, { queue.floatValue }, { true })
                try {
                    assertFalse(seat.queueAdmitted)
                    player.target(true)
                    h.advance(.08)
                    assertEquals(floatingRowSeat(600f, 760f, player.phase.value(), 1400f), seat.bottom, 0f)
                    assertTrue("Projection creates no additional active owner", h.clock.activeCount <= 1)
                    h.advance(1.0)
                    assertEquals(760f, seat.bottom, 0f)
                    queue.floatValue = 700f
                    assertTrue(seat.queueAdmitted)
                    assertEquals(700f, seat.bottom, 0f)
                    queue.floatValue = 1000f
                    assertFalse(seat.queueAdmitted)
                    player.target(false); h.advance(.08)
                    assertEquals(floatingRowSeat(600f, 760f, player.phase.value(), 1000f), seat.bottom, 0f)
                    browse.floatValue = 300f; full.floatValue = 460f; queue.floatValue = 420f
                    assertEquals(floatingRowSeat(300f, 460f, player.phase.value(), 420f), seat.bottom, 0f)
                    h.advance(1.0)
                    assertEquals(300f, seat.bottom, 0f)
                } finally { player.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun rootClockRechecksLiveCoordinatesWhileOnlyQueueOwnerMoves() {
        EngineHarness().use { h ->
            val queueOwner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            queueOwner.setBasis(ExpansionBasis(Rect(0f, 700f, 400f, 1500f), Rect(0f, 0f, 400f, 800f), 1f))
            var actualMeasuredBottom = 1400f // Live LayoutCoordinates are not snapshot state.
            val seat = FloatingRowSeatProjection({ 600f }, { 760f }, { 1f },
                { actualMeasuredBottom }, { true }, { h.clock.stamp; Unit })
            try {
                assertFalse(seat.queueAdmitted)
                queueOwner.target(true)
                actualMeasuredBottom = 700f; h.advance(.02)
                assertTrue(seat.queueAdmitted)
                assertEquals(700f, seat.bottom, 0f)
                actualMeasuredBottom = 1000f; h.advance(.02)
                assertFalse(seat.queueAdmitted)
                assertEquals(760f, seat.bottom, 0f)
                assertEquals("Only the existing Queue owner produces frames", 1, h.clock.activeCount)
            } finally { queueOwner.dispose() }
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun keyboardLimitCannotAdmitAnOffscreenQueueOrCreateAnotherOwner() {
        EngineHarness().use { h ->
            val queue = mutableFloatStateOf(1400f)
            val limit = mutableFloatStateOf(500f)
            val seat = FloatingRowSeatProjection({ 600f }, { 760f }, { 1f },
                { queue.floatValue }, { true }, limit = { limit.floatValue })
            assertFalse(seat.queueAdmitted)
            assertEquals(500f, seat.bottom, 0f)
            queue.floatValue = 700f
            assertTrue(seat.queueAdmitted)
            assertEquals(500f, seat.bottom, 0f)
            queue.floatValue = 1000f
            assertFalse(seat.queueAdmitted)
            limit.floatValue = 760f
            assertEquals(760f, seat.bottom, 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun keyboardMovesOriginalControlBasisAsOneRowAndHidingRestoresIt() {
        val first = Rect(20f, 670f, 110f, 760f)
        val second = Rect(210f, 650f, 300f, 740f)
        val shiftedFirst = floatingRowOriginal(first, 760f, 500f)
        val shiftedSecond = floatingRowOriginal(second, 760f, 500f)
        assertEquals(Rect(20f, 410f, 110f, 500f), shiftedFirst)
        assertEquals(Rect(210f, 390f, 300f, 480f), shiftedSecond)
        assertEquals(first.width, shiftedFirst.width, 0f)
        assertEquals(second.bottom - first.bottom, shiftedSecond.bottom - shiftedFirst.bottom, 0f)
        assertEquals(first, floatingRowOriginal(first, 760f, 760f))
        assertEquals(second, floatingRowOriginal(second, 760f, null))
        val body = Rect(-400f, 410f, 65f, 500f)
        val projection = toastButtonProjection(body, shiftedFirst, 20f)
        assertEquals(.5f, projection.alpha, 0f)
        assertEquals(10f, projection.offsetY, 0f)
        assertFalse(projection.uncovered)
        assertEquals(1f, toastButtonProjection(body, shiftedSecond, 20f).alpha, 0f)
    }

}
