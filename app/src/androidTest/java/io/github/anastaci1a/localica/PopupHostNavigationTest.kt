package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupHostNavigationTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun settingsBackDuringRetractLeavesWithSubmenu() = navigate(settings = true, expanding = false)
    @Test fun settingsBackDuringExpandThenBackLeavesWithSubmenu() = navigate(settings = true, expanding = true)
    @Test fun subviewBackDuringRetractLeavesWithPage() = navigate(settings = false, expanding = false)
    @Test fun subviewBackDuringExpandThenBackLeavesWithPage() = navigate(settings = false, expanding = true)

    private fun navigate(settings: Boolean, expanding: Boolean) {
        var page by mutableStateOf(true)
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        var parentView: View? = null
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize().background(Color.Blue))
            @Composable fun Body() {
                parentView = LocalView.current
                anchor = rememberGlassAnchor()
                Column(Modifier.fillMaxSize().background(Color.Blue).padding(24.dp)) {
                    SettingsLink("Popup home", anchor = anchor) { open = true }
                    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor, LocalMotionSpeed provides .5f) {
                        GlassDialog({ open = false }) {
                            popupView = LocalView.current
                            Box(Modifier.size(240.dp).background(Color.Red))
                        }
                    }
                }
            }
            val shownPage = page
            if (settings) SettingsPage(if (shownPage) "Motion" else "Settings", { page = false }, navigationDepth = if (page) 1 else 0) {
                if (shownPage) Body() else Box(Modifier.fillMaxSize().background(Color.Blue))
            }
            else if (page) GlassDialog({ page = false }, GlassPlacement.Page) { Body() }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { open = true }
        compose.advanceMorphOpening(if (expanding) 480 else 3200, anchor) { popupView }
        fun back(view: View?, condition: () -> Boolean) {
            compose.waitUntil(5000) { view?.hasWindowFocus() == true }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressKeyCode(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000, condition)
        }
        // Back during expansion must reverse the same window, then the next Back
        // navigates while it is still returning. Also cover an ordinary dismissal.
        if (expanding) back(popupView) { !open }
        else compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("Return is in flight before navigation", anchor.modalPresent && anchor.contact.expansion.value > 0f)
        val before = redFrame()
        assertTrue("Fixture presents popup foreground before navigation: $before", before.pixels > 100)
        back(parentView) { !page }
        compose.mainClock.advanceTimeBy(16)
        assertTrue("Navigation preserves the popup in the first outgoing frame", redFrame().pixels > 100)
        assertTrue("The same native popup keeps retracting", popupView?.isAttachedToWindow == true)
        compose.mainClock.advanceTimeBy(32)
        val during = redFrame()
        assertTrue("Outgoing popup fades continuously with its host: $before -> $during",
            during.peak in 1 until before.peak)
        compose.mainClock.advanceTimeBy(208)
        val after = redFrame()
        assertEquals("Popup cannot outlive its outgoing host layer over the destination", 0, after.pixels)
        compose.mainClock.advanceTimeBy(1600)
    }

    private data class RedFrame(val pixels: Int, val peak: Int)

    private fun redFrame(): RedFrame {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Native window drawing is independent of the paused Compose clock.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        return try {
            var count = 0
            var peak = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                val excess = android.graphics.Color.red(c) - maxOf(android.graphics.Color.green(c), android.graphics.Color.blue(c))
                peak = maxOf(peak, excess)
                if (android.graphics.Color.red(c) > 80 && excess > 32) count++
            }
            RedFrame(count, peak)
        } finally { bitmap.recycle() }
    }
}
