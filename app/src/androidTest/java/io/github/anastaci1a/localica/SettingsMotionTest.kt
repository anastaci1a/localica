package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothBackControlsReverseThePageEntrance() {
        var depth by mutableIntStateOf(0)
        var dialogView: View? = null
        var phase = "initial"
        var backCallbacks = 0
        fun inputReady(): Boolean {
            val view = dialogView ?: return false
            val window = (view.parent as? DialogWindowProvider)?.window ?: return false
            val disabled = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            return view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
                window.attributes.flags and disabled == 0
        }
        fun trace(event: String) {
            val view = dialogView
            val flags = (view?.parent as? DialogWindowProvider)?.window?.attributes?.flags
            Log.i("SettingsMotionFixture", "timeMs=${SystemClock.elapsedRealtime()} phase=$phase event=$event " +
                "depth=$depth callbacks=$backCallbacks view=${view?.let { System.identityHashCode(it) }} " +
                "attached=${view?.isAttachedToWindow} shown=${view?.isShown} focus=${view?.hasWindowFocus()} flags=$flags")
        }
        compose.setContent { LocalicaTheme {
            SettingsPage(if (depth == 0) "Settings" else "Motion", {
                backCallbacks++
                trace("onBack")
                depth = 0
            }, navigationDepth = depth) {
                // Capture the Dialog's LocalView, not the Activity's composition local.
                val view = LocalView.current
                SideEffect { dialogView = view }
                Text("Page content", Modifier.fillMaxWidth().padding(16.dp).testTag("settings-content"))
            }
        } }
        val home = compose.onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot.left
        for (systemBack in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { depth = 1 }
            compose.mainClock.advanceTimeBy(64)
            assertTrue(compose.onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot.left > home)
            compose.mainClock.advanceTimeBy(500)
            compose.runOnUiThread {
                phase = if (systemBack) "system-back" else "button-back"
                trace("await-input")
            }
            // Simulated animation time does not establish native window input readiness.
            // Keep the clock paused throughout this real-time, separately bounded precondition.
            try {
                compose.waitUntil(5000) { compose.runOnUiThread { inputReady() } }
            } finally { compose.runOnUiThread { trace("input-readiness-complete") } }
            compose.runOnUiThread {
                assertTrue("The actual Settings Dialog must own native input before Back", inputReady())
                trace("before-back")
            }
            if (systemBack) assertTrue("UiDevice must inject native Back",
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack())
            else compose.onNodeWithContentDescription("Back from settings").performClick()
            try { compose.waitUntil(5000) { depth == 0 } }
            finally { compose.runOnUiThread { trace("after-back-wait") } }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("Back enters from the left", compose.onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot.left < home)
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
        }
    }
}
