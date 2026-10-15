package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStateMotionTest {
    @Test fun stateFadesRespectNormalFasterAndReducedMotion() {
        assertEquals(300, controlStateDuration(false, 4f / 3f))
        assertEquals(200, controlStateDuration(false, 2f))
        assertEquals(0, controlStateDuration(true, 4f / 3f))
        assertEquals(0, controlStateDuration(true, 2f))
    }

    @Test fun easeIsSymmetricMonotonicAndSettlesExactly() {
        assertEquals(0f, ControlStateEasing.transform(0f), 0f)
        assertEquals(1f, ControlStateEasing.transform(1f), 0f)
        var previous = 0f
        for (step in 0..100) {
            val time = step / 100f
            val value = ControlStateEasing.transform(time)
            assertTrue(value >= previous)
            assertEquals(value, 1f - ControlStateEasing.transform(1f - time), .0001f)
            previous = value
        }
        assertEquals(.5f, ControlStateEasing.transform(.5f), .0001f)
    }
}
