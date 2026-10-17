package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStateMotionTest {
    @Test fun semanticActivationIsFasterAndBothDirectionsRespectMotionSettings() {
        assertEquals(180, controlStateDuration(true, false, 4f / 3f))
        assertEquals(270, controlStateDuration(false, false, 4f / 3f))
        assertEquals(120, controlStateDuration(true, false, 2f))
        assertEquals(180, controlStateDuration(false, false, 2f))
        for (active in listOf(false, true)) for (speed in listOf(4f / 3f, 2f))
            assertEquals(0, controlStateDuration(active, true, speed))
    }

    @Test fun easeStartsPromptlyIsMonotonicAndSettlesExactly() {
        assertEquals(0f, ControlStateEasing.transform(0f), 0f)
        assertEquals(1f, ControlStateEasing.transform(1f), 0f)
        assertTrue("First 16 ms of activation makes visible progress", ControlStateEasing.transform(16f / 180f) > .1f)
        var previous = 0f
        for (step in 0..100) {
            val value = ControlStateEasing.transform(step / 100f)
            assertTrue(value >= previous)
            previous = value
        }
    }
}
