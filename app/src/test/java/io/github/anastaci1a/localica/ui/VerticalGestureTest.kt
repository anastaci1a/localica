package io.github.anastaci1a.localica.ui
import org.junit.Assert.*
import org.junit.Test

class VerticalGestureTest {
    @Test fun flickOverridesSmallDrawerTravelInBothDirections() {
        assertTrue(settleOpen(12f, 420f, .02f, true))
        assertFalse(settleOpen(-12f, -420f, .98f, false))
    }
    @Test fun slowDragsHaveAUsefulDistanceThreshold() {
        assertFalse(settleOpen(30f, 0f, .06f, true))
        assertTrue(settleOpen(80f, 0f, .16f, true))
        assertFalse(settleOpen(-80f, 0f, .84f, false))
    }
    @Test fun releaseVelocityCanReverseAnAlreadyLongPull() {
        assertTrue(settleOpen(-100f, 420f, .7f, false))
        assertFalse(settleOpen(100f, -420f, .3f, true))
    }
    @Test fun playerDismissalRequiresTheHigherVelocityOrDistance() {
        assertTrue(settleOpen(-20f, -340f, .96f, false, 400f))
        assertFalse(settleOpen(-20f, -440f, .96f, false, 400f))
        assertFalse(settleOpen(-80f, 0f, .84f, false, 400f))
    }
}
