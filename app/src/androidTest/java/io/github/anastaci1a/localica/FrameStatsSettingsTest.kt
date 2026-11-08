package io.github.anastaci1a.localica

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Black-box operator path: also runnable against the pre-feature app for the red proof. */
class FrameStatsSettingsTest {
    @Test fun aboutOffersExplicitStartAndStopWithoutSharingAnything() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // The label changes in place; a cached lookup saw it 1.5-15 s late on API 36 (#620).
        fun click(selector: BySelector) {
            val target = device.findFresh(selector, 15_000)
            assertNotNull("Missing operator control: $selector", target)
            target!!.click()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.desc("More"))
            click(By.text("Settings"))
            click(By.textContains("About and licenses"))
            click(By.text("Start frame capture"))
            click(By.text("Stop frame capture"))
            assertNotNull(device.findFresh(By.text("Start frame capture"), 5000))
        }
    }
}
