package io.github.anastaci1a.localica

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** Black-box operator path: also runnable against the pre-feature app for the red proof. */
class FrameStatsSettingsTest {
    @Test fun aboutOffersExplicitStartAndStopWithoutSharingAnything() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun click(selector: BySelector, expectedY: Int? = null): Rect {
            assertNotNull("Missing operator control: $selector", device.findFresh(selector, 15_000))
            // Admit one physical click after native readiness; never retry a missed action.
            device.waitForIdle(5_000)
            val until = SystemClock.uptimeMillis() + 15_000
            var previous: Rect? = null
            var stable = 0
            while (SystemClock.uptimeMillis() < until) {
                val target = device.findFresh(selector, 1_000)
                val bounds = target?.visibleBounds
                stable = if (bounds != null && !bounds.isEmpty && bounds == previous) stable + 1 else 0
                if (stable >= 3 && target?.isEnabled == true) {
                    if (expectedY != null) assertEquals("Capture target must stay anchored", expectedY, bounds!!.centerY())
                    target.click(); return Rect(bounds)
                }
                previous = bounds; Thread.sleep(50)
            }
            throw AssertionError("Operator control never settled: $selector")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.desc("More"))
            click(By.text("Settings"))
            click(By.textContains("About and licenses"))
            click(By.text("Capture scenario: untagged"))
            val captureY = click(By.text("Start frame capture")).centerY()
            assertNotNull("Recording retains the tag row", device.findFresh(By.text("Capture scenario: list-smallest"), 5000))
            click(By.text("Stop frame capture"), captureY)
            assertNotNull("One settled Stop click must restore Start", device.findFresh(By.text("Start frame capture"), 5000))
            assertEquals("Stop must preserve the capture target", captureY, device.findFresh(By.text("Start frame capture"), 5000)!!.visibleBounds.centerY())
        }
    }
}
