package io.github.anastaci1a.localica

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** Disposable negative qualification ref only. Never integrate this intentional failure. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CiInfrastructureProductFailureWitnessTest {
    @Test fun a_deliberateProductAssertion() {
        assertEquals("Intentional JUnit ComparisonFailure must block infrastructure recovery", "expected", "actual")
    }

    @Test fun b_keepInstrumentationActiveUntilTheInjectedEmulatorLoss() {
        // The qualification driver terminates the emulator after the preceding assertion is reported.
        SystemClock.sleep(60_000)
    }
}
