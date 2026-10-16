package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlGestureIntentTest {
    private val horizontal = ControlScrollAxis.Horizontal
    private val vertical = ControlScrollAxis.Vertical

    @Test fun diagonalJitterCannotClaimBeforeEitherAxisCrossesSlop() {
        val intent = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Pending, intent.move(8f, 8f, 16))
        assertEquals(ControlGestureOwner.Scroll, intent.move(9f, 18f, 32))
    }

    @Test fun jitterRemainsPendingAndFastSweepYieldsToAvailableParent() {
        val intent = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Pending, intent.move(1f, -5f, 16))
        assertEquals(ControlGestureOwner.Scroll, intent.move(1f, -18f, 32))
        assertEquals(ControlGestureOwner.Scroll, intent.move(0f, 80f, 800))
    }

    @Test fun slowMovementAndHeldContactRetainControlThroughFastReversal() {
        for (elapsed in listOf(110L, 500L)) {
            val intent = ControlGestureIntent(10f, vertical)
            assertEquals(ControlGestureOwner.Control, intent.move(0f, -12f, elapsed))
            assertEquals(ControlGestureOwner.Control, intent.move(0f, 100f, elapsed + 16))
        }
    }

    @Test fun absentOrPerpendicularParentCannotStealFastAdjustment() {
        for (parent in listOf(ControlScrollAxis.None, vertical)) {
            val intent = ControlGestureIntent(10f, parent, horizontal)
            assertEquals(ControlGestureOwner.Control, intent.move(50f, 2f, 16))
        }
    }

    @Test fun crossAxisScrollYieldsWithoutRequiringHighVelocity() {
        val intent = ControlGestureIntent(10f, vertical, horizontal)
        assertEquals(ControlGestureOwner.Scroll, intent.move(1f, 15f, 200))
    }

    @Test fun sameAxisSlowAdjustmentAndFastScrollAreDistinctInBothDirections() {
        for (axis in listOf(horizontal, vertical)) for (sign in listOf(-1, 1)) {
            val dx = if (axis == horizontal) 15f * sign else 0f
            val dy = if (axis == vertical) 15f * sign else 0f
            assertEquals(ControlGestureOwner.Scroll, ControlGestureIntent(10f, axis, axis).move(dx, dy, 16))
            assertEquals(ControlGestureOwner.Control, ControlGestureIntent(10f, axis, axis).move(dx, dy, 150))
        }
    }

    @Test fun cancellationIsTerminalRegardlessOfPriorOwner() {
        for (elapsed in listOf(16L, 200L)) {
            val intent = ControlGestureIntent(10f, horizontal, horizontal)
            intent.move(20f, 0f, elapsed)
            intent.cancel()
            assertEquals(ControlGestureOwner.Cancelled, intent.move(-100f, 0f, 400))
        }
    }
}
