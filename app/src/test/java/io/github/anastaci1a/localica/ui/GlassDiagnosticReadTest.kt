package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.MotionFloat
import org.junit.Assert.*
import org.junit.Test

/** #920: a glass draw diagnostic samples motions without presenting them, so it cannot change what the engine does next. */
class GlassDiagnosticReadTest {
    @Test fun samplingThroughTheObserverNeverPresentsAMotionOrBlocksItsSnap() {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, .25f, 1f)
            var sampled = Float.NaN
            h.clock.observe { sampled = motion.value() }
            assertEquals(.25f, sampled)
            assertFalse("a diagnostic sample is not a presentation", motion.presented)
            motion.initialize(.75f) // refused (IllegalStateException) once a motion has been presented
            assertEquals(.75f, motion.value())
            assertTrue("an ordinary read still presents it", motion.presented)
        }
    }
}
