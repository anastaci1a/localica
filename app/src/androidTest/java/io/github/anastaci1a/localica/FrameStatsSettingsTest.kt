package io.github.anastaci1a.localica

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Black-box operator path: also runnable against the pre-feature app for the red proof. */
class FrameStatsSettingsTest {
    @Test fun aboutOffersExplicitStartAndStopWithoutSharingAnything() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val automation = instrumentation.getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        // UiAutomation answers from a node cache that only accessibility events invalidate. On the
        // API 36 emulator the label's change reached that cache about 1.5 s after the app drew it, and
        // in failing runs not within 5-15 s (#620). Drop the cache so each lookup reads the app's tree.
        fun find(selector: BySelector, timeout: Long): UiObject2? {
            val deadline = SystemClock.uptimeMillis() + timeout
            while (true) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                device.findObject(selector)?.let { return it }
                if (SystemClock.uptimeMillis() >= deadline) return null
                SystemClock.sleep(100)
            }
        }
        fun click(selector: BySelector) {
            val target = find(selector, 15_000)
            assertNotNull("Missing operator control: $selector", target)
            target!!.click()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.desc("More"))
            click(By.text("Settings"))
            click(By.textContains("About and licenses"))
            click(By.text("Start frame capture"))
            click(By.text("Stop frame capture"))
            assertNotNull(find(By.text("Start frame capture"), 5000))
        }
    }
}
