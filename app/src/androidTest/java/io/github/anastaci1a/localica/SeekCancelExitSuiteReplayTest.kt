package io.github.anastaci1a.localica

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/** Diagnostic only: retain predecessor process state between original release witnesses. */
class SeekCancelExitSuiteReplayTest {
    @Test fun repeatOriginalSliderCancelWitnessWithoutRestartingTheSuiteProcess() {
        repeat(20) { iteration ->
            val result = JUnitCore().run(Request.method(SeekCancelExitTest::class.java,
                "sliderCancelReleaseFadesInPlaceWithoutMissingFrames"))
            android.util.Log.i("SeekCancelSuiteReplay", "iteration=${iteration + 1} " +
                "run=${result.runCount} failures=${result.failureCount} ignored=${result.ignoreCount} " +
                "elapsedMs=${result.runTime}")
            assertTrue("Original slider cancel witness failed on process iteration ${iteration + 1}: " +
                result.failures.joinToString("\n") { it.trace },
                result.runCount == 1 && result.ignoreCount == 0 && result.wasSuccessful())
        }
    }
}
