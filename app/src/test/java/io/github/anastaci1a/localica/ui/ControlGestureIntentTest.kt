package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlGestureIntentTest {
    private val up = ControlScrollDirection.Up
    private val down = ControlScrollDirection.Down
    private val left = ControlScrollDirection.Left
    private val right = ControlScrollDirection.Right
    private val vertical = ControlScrollDirection.Vertical
    private val none = ControlScrollDirection.None

    @Test fun diagonalJitterCannotClaimBeforeEitherAxisCrossesSlop() {
        val intent = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Pending, intent.move(8f, 8f, 16))
        assertEquals(ControlGestureOwner.Scroll, intent.move(9f, 18f, 32))
    }

    @Test fun unheldTravelYieldsOnlyInDirectionsAParentCanStillMove() {
        // A list at its top can only reveal content below: a finger moving up.
        assertEquals(ControlGestureOwner.Scroll, ControlGestureIntent(10f, up).move(0f, -20f, 40))
        assertEquals("Swiping down at the top activates", ControlGestureOwner.Control,
            ControlGestureIntent(10f, up).move(0f, 20f, 40))
        // At the bottom only a downward finger scrolls back.
        assertEquals(ControlGestureOwner.Scroll, ControlGestureIntent(10f, down).move(0f, 20f, 40))
        assertEquals("Swiping up at the bottom activates", ControlGestureOwner.Control,
            ControlGestureIntent(10f, down).move(0f, -20f, 40))
        for (dy in listOf(-20f, 20f)) assertEquals("A menu that cannot scroll never blocks activation",
            ControlGestureOwner.Control, ControlGestureIntent(10f, none).move(0f, dy, 16))
    }

    @Test fun slowUnheldVerticalTravelStillScrollsAndHeldContactLocks() {
        val slow = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Pending, slow.move(0f, -6f, 200))
        assertEquals(ControlGestureOwner.Scroll, slow.move(0f, -12f, ControlHoldLockMillis - 1))
        val held = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Pending, held.move(0f, 4f, ControlHoldLockMillis + 100))
        assertEquals(ControlGestureOwner.Control, held.move(0f, -40f, ControlHoldLockMillis + 116))
        assertEquals("Ownership survives a fast reversal", ControlGestureOwner.Control, held.move(0f, 200f, 600))
    }

    @Test fun horizontalSlideLocksImmediatelyInsideAVerticalParent() {
        for (dx in listOf(-16f, 16f)) {
            val intent = ControlGestureIntent(10f, vertical)
            assertEquals(ControlGestureOwner.Control, intent.move(dx, 6f, 16))
            assertEquals("Later vertical travel cannot scroll", ControlGestureOwner.Control, intent.move(dx, 120f, 60))
        }
    }

    @Test fun dominantAxisChoosesTheDirectionAndTiesCountAsVertical() {
        assertEquals(up, ControlGestureIntent.direction(-5f, -5f))
        assertEquals(down, ControlGestureIntent.direction(5f, 5f))
        assertEquals(left, ControlGestureIntent.direction(-6f, 5f))
        assertEquals(right, ControlGestureIntent.direction(6f, -5f))
    }

    @Test fun sameAxisParentScrollsBeforeTheHoldAndControlOwnsAfterIt() {
        for (direction in listOf(left, right)) {
            val dx = if (direction == left) -15f else 15f
            assertEquals(ControlGestureOwner.Scroll, ControlGestureIntent(10f, direction).move(dx, 0f, 16))
            assertEquals(ControlGestureOwner.Control,
                ControlGestureIntent(10f, direction).move(dx, 0f, ControlHoldLockMillis))
        }
    }

    @Test fun parentWinnerCannotBecomeControlAfterDirectionChange() {
        val intent = ControlGestureIntent(10f, vertical)
        assertEquals(ControlGestureOwner.Scroll, intent.move(0f, 25f, 16))
        intent.hold()
        assertEquals(ControlGestureOwner.Scroll, intent.move(80f, 0f, 400))
    }

    @Test fun stationaryHoldAcquiresOnlyAnUnresolvedContact() {
        val pending = ControlGestureIntent(10f, vertical)
        pending.hold()
        assertEquals(ControlGestureOwner.Control, pending.move(0f, 100f, 40))
        val scrolling = ControlGestureIntent(10f, vertical)
        scrolling.move(0f, 30f, 16); scrolling.hold()
        assertEquals(ControlGestureOwner.Scroll, scrolling.owner)
        scrolling.cancel(); scrolling.hold()
        assertEquals(ControlGestureOwner.Cancelled, scrolling.owner)
    }

    @Test fun cancellationIsTerminalRegardlessOfPriorOwner() {
        for (elapsed in listOf(16L, 400L)) {
            val intent = ControlGestureIntent(10f, ControlScrollDirection.Horizontal)
            intent.move(20f, 0f, elapsed)
            intent.cancel()
            assertEquals(ControlGestureOwner.Cancelled, intent.move(-100f, 0f, 600))
        }
    }

    @Test fun scrollEdgesMapToFingerDirectionsThroughReversal() {
        assertEquals(up, controlScrollDirections(true, canScrollForward = true, canScrollBackward = false, reverse = false))
        assertEquals(down, controlScrollDirections(true, canScrollForward = false, canScrollBackward = true, reverse = false))
        assertEquals(down, controlScrollDirections(true, canScrollForward = true, canScrollBackward = false, reverse = true))
        assertEquals(left, controlScrollDirections(false, canScrollForward = true, canScrollBackward = false, reverse = false))
        assertEquals(right, controlScrollDirections(false, canScrollForward = true, canScrollBackward = false, reverse = true))
        assertEquals(vertical, controlScrollDirections(true, canScrollForward = true, canScrollBackward = true, reverse = false))
        assertEquals(none, controlScrollDirections(false, canScrollForward = false, canScrollBackward = false, reverse = false))
    }

    @Test fun parentChainUnitesEveryAncestorsLiveDirections() {
        var listTop = true
        val drawer = ControlScrollNode { down }
        val list = ControlScrollNode { if (listTop) up else vertical }.also { it.parent = drawer }
        assertEquals(vertical, list.directions())
        listTop = false
        assertEquals(vertical, list.directions())
        assertEquals(down, drawer.directions())
    }

    @Test fun playerSurfaceMovesBothWaysExpandedUpCollapsedAndNeverDuringAScrub() {
        assertEquals(vertical, playerDragDirections(expanded = true, scrubbing = false))
        assertEquals(up, playerDragDirections(expanded = false, scrubbing = false))
        assertEquals(none, playerDragDirections(expanded = true, scrubbing = true))
    }
}
