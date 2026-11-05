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
        val before = redPixels()
        assertTrue("Fixture presents popup foreground before navigation: $before", before > 100)
        back(parentView) { !page }
        compose.mainClock.advanceTimeBy(256)
        val after = redPixels()
        assertEquals("Popup cannot outlive its outgoing host layer over the destination", 0, after)
        compose.mainClock.advanceTimeBy(1600)
    }

    private fun redPixels(): Int {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Native window drawing is independent of the paused Compose clock.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        return try {
            var count = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) > 160 && android.graphics.Color.green(c) < 80 && android.graphics.Color.blue(c) < 80) count++
            }
            count
        } finally { bitmap.recycle() }
    }
}
