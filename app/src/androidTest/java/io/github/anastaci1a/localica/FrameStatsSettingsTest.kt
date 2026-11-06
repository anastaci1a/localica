package io.github.anastaci1a.localica

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Black-box operator path: also runnable against the pre-feature app for the red proof. */
class FrameStatsSettingsTest {
    @Test fun aboutOffersExplicitStartAndStopWithoutSharingAnything() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun click(selector: BySelector) {
            assertNotNull("Missing operator control: $selector", device.wait(Until.findObject(selector), 15_000))
            // The capture tag row leaves/rejoins the popup when recording changes. A visible
            // label can precede its final native hit bounds; admit exactly one settled click.
            device.waitForIdle(5_000)
            val until = SystemClock.uptimeMillis() + 15_000
            var previous: Rect? = null
            var stable = 0
            while (SystemClock.uptimeMillis() < until) {
                val target = device.findObject(selector)
                val bounds = target?.visibleBounds
                stable = if (bounds != null && !bounds.isEmpty && bounds == previous) stable + 1 else 0
                if (stable >= 3 && target?.isEnabled == true) { target.click(); return }
                previous = bounds; Thread.sleep(50)
            }
            throw AssertionError("Operator control never settled: $selector")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.desc("More"))
            click(By.text("Settings"))
            click(By.textContains("About and licenses"))
            click(By.text("Capture scenario: untagged"))
            click(By.text("Start frame capture"))
            click(By.text("Stop frame capture"))
            assertNotNull("One settled Stop click must restore Start", device.wait(Until.findObject(By.text("Start frame capture")), 5000))
        }
    }
}
