package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class GlassMotionTest {
    @Test fun pinchIsLinearAndFullyReversible() {
        assertEquals(.25f, pinchProgress(1.2f, true), .0001f)
        assertEquals(.5f, pinchProgress(1.4f, true), .0001f)
        assertEquals(0f, pinchProgress(1f, true), 0f)
        assertEquals(0f, pinchProgress(1.1f, false), 0f)
        assertEquals(1f, pinchProgress(.5f, false), 0f)
    }
    @Test fun magneticPositionIsContinuousMonotonicAndBounded() {
        var last = 0f
        for (step in 0..400) {
            val value = magneticTabPosition(step / 100f)
            assertTrue(value >= last)
            assertTrue(value in 0f..4f)
            last = value
        }
        assertEquals(.5f, magneticTabPosition(.5f), .0001f)
        assertTrue(magneticTabPosition(.2f) < .12f)
    }
}
