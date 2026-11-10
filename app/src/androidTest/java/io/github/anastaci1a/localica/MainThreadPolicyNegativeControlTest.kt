package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Test

/** Run on its own proof ref: success means the guard is broken. Never part of the green suite. */
class MainThreadPolicyNegativeControlTest {
    @Test fun intentionalMainThreadReadMustKillInstrumentation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FileInputStream("/proc/version").use { it.read() }
        }
        error("StrictMode failed to kill instrumentation after an intentional main-thread read")
    }
}
