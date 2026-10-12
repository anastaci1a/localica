package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
        compose.setContent { LocalicaTheme {
            SettingsPage(if (depth == 0) "Settings" else "Motion", { depth = 0 }, navigationDepth = depth) {
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
            if (systemBack) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            else compose.onNodeWithContentDescription("Back from settings").performClick()
            compose.waitUntil(5000) { depth == 0 }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("Back enters from the left", compose.onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot.left < home)
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
        }
    }
}
