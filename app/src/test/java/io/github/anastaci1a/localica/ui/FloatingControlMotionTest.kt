package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlMotionTest {
    @Test fun sharedDurationsRespectNormalFasterAndReducedMotion() {
        assertEquals(300, floatingControlDuration(false, 4f / 3f))
        assertEquals(200, floatingControlDuration(false, 2f))
        assertEquals(0, floatingControlDuration(true, 4f / 3f))
        assertEquals(0, floatingControlDuration(true, 2f))
    }
}
