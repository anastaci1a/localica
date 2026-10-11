package io.github.anastaci1a.localica.queue

import org.junit.Assert.*
import org.junit.Test

class PreviousBehaviorTest {
    @Test fun defaultUsesStrictFiveSecondBoundary() {
        val settings = QueueSettings()
        assertFalse(settings.restartOnPrevious(0))
        assertFalse(settings.restartOnPrevious(4999))
        assertTrue(settings.restartOnPrevious(5000))
        assertTrue(settings.restartOnPrevious(90000))
    }
    @Test fun customThresholdAndUnconditionalModes() {
        val settings = QueueSettings(previousThresholdSeconds = 8)
        assertFalse(settings.restartOnPrevious(7999))
        assertTrue(settings.restartOnPrevious(8000))
        assertFalse(settings.copy(previousBehavior = PreviousBehavior.PreviousSong).restartOnPrevious(90000))
        assertTrue(settings.copy(previousBehavior = PreviousBehavior.RestartSong).restartOnPrevious(0))
    }
}
