package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveReducedMotionTest {
    @Test fun eitherRequestSuppressesDecorativeMotion() {
        assertTrue(effectiveReducedMotion(false, 0f))
        assertTrue(effectiveReducedMotion(true, 0f))
        for (scale in listOf(.5f, 1f, 2f, 10f)) {
            assertTrue(effectiveReducedMotion(true, scale))
            assertFalse(effectiveReducedMotion(false, scale))
        }
    }
}
