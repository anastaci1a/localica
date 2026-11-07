package io.github.anastaci1a.localica

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.anastaci1a.localica.diagnostics.FrameStats
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Operator path through the real controls, plus the capture's own report. */
class FrameStatsSettingsTest {
    @Test fun aboutOffersExplicitStartAndStopWithoutSharingAnything() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun click(selector: BySelector) {
            val target = device.wait(Until.findObject(selector), 15_000)
            assertNotNull("Missing operator control: $selector", target)
            target.click()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.desc("More"))
            click(By.text("Settings"))
            click(By.textContains("About and licenses"))
            click(By.text("Start frame capture"))
            click(By.text("Stop frame capture"))
            assertNotNull(device.wait(Until.findObject(By.text("Start frame capture")), 5000))
            // Start reaches the already-open Settings window without recomposing the app (#620).
            val windows = JSONObject(FrameStats.report()).getJSONArray("windows")
            val names = (0 until windows.length()).map { windows.getJSONObject(it).getString("window") }
            assertTrue("Measure the open Settings window too: $names", names.any { it.startsWith("modal-") })
        }
    }
}
