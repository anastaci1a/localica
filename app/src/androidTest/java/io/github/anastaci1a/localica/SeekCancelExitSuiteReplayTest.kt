package io.github.anastaci1a.localica

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore


/** Diagnostic only: retain predecessor process state between original release witnesses. */
class SeekCancelExitSuiteReplayTest {
    @Test fun repeatOriginalReleaseClassWithoutRestartingTheSuiteProcess() {
        repeat(20) { iteration ->
            val result = JUnitCore().run(SeekCancelExitTest::class.java)
            android.util.Log.i("SeekCancelSuiteReplay", "iteration=${iteration + 1} " +
                "run=${result.runCount} failures=${result.failureCount} ignored=${result.ignoreCount} " +
                "elapsedMs=${result.runTime}")
            assertTrue("Original release class failed on process iteration ${iteration + 1}: " +
                result.failures.joinToString("\n") { it.trace },
                result.runCount == 7 && result.ignoreCount == 0 && result.wasSuccessful())
        }
    }
}
