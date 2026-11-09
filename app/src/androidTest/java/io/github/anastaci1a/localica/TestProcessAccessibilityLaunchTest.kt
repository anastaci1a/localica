package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Reproduces #730. UiAutomation connecting turns accessibility on for every process. AndroidX
 * `ActivityScenario.launch` first starts a Bootstrap/Empty activity in the test APK's own process
 * and finishes it at once. On API 26 a state change delivered to that process after the finished
 * window was detached runs `ViewRootImpl$AccessibilityInteractionConnectionManager
 * .onAccessibilityStateChanged` against a null `mView` and kills the process (framework bug).
 * That process hosts the fixture DocumentsProviders, so the next picker step fails.
 *
 * The test recreates the overlap: UiAutomation is reconnected (a flag change disconnects, then reconnects it) at staggered offsets
 * while scenarios launch.
 */
class TestProcessAccessibilityLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun uiAutomationConnectingDuringFixtureWindowLaunchDoesNotCrashTheTestProcess() {
        shell("logcat -b crash -c")
        for (cycle in 0 until CYCLES) {
            instrumentation.getUiAutomation(0)
            val offset = (cycle % 20) * 10L
            val connect = thread(name = "a11y-connect-$cycle") { Thread.sleep(offset); instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
            ActivityScenario.launch(ComponentActivity::class.java).use { it.onActivity {} }
            connect.join()
        }
        val crashes = shell("logcat -b crash -d")
        assertFalse(crashes, crashes.contains("AccessibilityInteractionConnectionManager"))
    }
    private companion object { const val CYCLES = 60 }
}
