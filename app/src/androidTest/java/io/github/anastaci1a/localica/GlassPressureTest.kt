package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassPressureTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun configureWindowLikeTheApp() {
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @Test fun heldSortLensCompressesAtTheBoundaryAndRecoversOnRelease() {
        compose.setContent { LocalicaTheme { Box(Modifier.width(260.dp).padding(top = 100.dp)) {
            GlassChoices(listOf("Title", "Artist", "Year"), 0) { }
        } } }
        val lens = compose.onNodeWithTag("elastic-lens-face", useUnmergedTree = true)
        val initial = lens.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Title").performTouchInput { down(center); moveBy(Offset(0f, -initial.height * 2)) }
        compose.mainClock.advanceTimeBy(300)
        val held = lens.fetchSemanticsNode().boundsInRoot
        assertTrue("A held overdrag compresses the vertical lens: $initial -> $held", held.height < initial.height * .97f)
        compose.onNodeWithText("Title").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(initial.height, lens.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.mainClock.autoAdvance = true
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Test fun longFieldLabelsRemainPartOfTheEditableControl() {
        var text by mutableStateOf("")
        var keyboardVisible = false
        compose.setContent { LocalicaTheme { Box(Modifier.width(240.dp)) {
            keyboardVisible = WindowInsets.isImeVisible
            GlassTextField(text, { text = it }, label = { androidx.compose.material3.Text("Maximum queues (blank = unlimited)") }, singleLine = true)
        } } }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performClick()
        compose.waitUntil(5000) { keyboardVisible }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performTextInput("123")
        compose.runOnIdle { assertEquals("123", text) }
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { !compose.onNodeWithText("Maximum queues (blank = unlimited)").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").assertIsNotFocused()
        compose.runOnIdle { assertEquals("123", text) }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performClick()
        compose.onNodeWithText("Maximum queues (blank = unlimited)").assertIsFocused().performTextReplacement("456")
        compose.runOnIdle { assertEquals("456", text) }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun backEndsEditingBeforeClosingItsDialog() {
        var open by mutableStateOf(true)
        var value by mutableStateOf("")
        var keyboardVisible = false
        compose.setContent { LocalicaTheme { if (open) GlassDialog({ open = false }) {
            keyboardVisible = WindowInsets.isImeVisible
            GlassTextField(value, { value = it }, label = { androidx.compose.material3.Text("Dialog draft") })
        } } }
        compose.onNodeWithText("Dialog draft").performClick()
        compose.waitUntil(5000) { keyboardVisible }
        compose.onNodeWithText("Dialog draft").performTextInput("Keep this")
        compose.runOnIdle { assertEquals("Keep this", value) }
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { !compose.onNodeWithText("Dialog draft").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        compose.onNodeWithText("Dialog draft").assertIsNotFocused()
        compose.runOnIdle { assertTrue(open); assertEquals("Keep this", value) }
        device.pressBack()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(open) }
    }
}
