package io.github.anastaci1a.localica

import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Detect framework restoration or test setup replacing the installed policy after app binding. */
class MainThreadPolicyInstallationTest {
    @Test fun allRequestedDetectorsAndFatalPenaltiesSurviveApplicationBinding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("expectNonDebuggableTarget") == "true") {
            assertEquals(0, instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        }
        instrumentation.runOnMainSync {
            val expected = StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork()
                .detectCustomSlowCalls().detectUnbufferedIo()
                .penaltyLog().penaltyDeath().build()
            // Compare platform serialization on the same SDK, without hardcoding private mask bits.
            assertEquals("Main policy changed after application binding",
                expected.toString(), StrictMode.getThreadPolicy().toString())
        }
    }
}
