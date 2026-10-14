package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ButtonGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedButtonsKeepTheirContactWithoutScrollingOrActivatingAfterDrag() {
        lateinit var scroll: ScrollState
        var clicks = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(450.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                GlassActionButton(R.drawable.ic_plus, "Glass action") { clicks++ }
                SettingsLink("Setting action") { clicks++ }
                ActionTextButton({ clicks++ }) { Text("Text action") }
                ActionIconButton({ clicks++ }, Modifier.testTag("icon-action")) { Text("Icon") }
                Spacer(Modifier.height(900.dp))
            }
        } }
        val actions = listOf(compose.onNodeWithText("Glass action"), compose.onNodeWithText("Setting action"),
            compose.onNodeWithText("Text action"), compose.onNodeWithTag("icon-action"))
        actions.forEachIndexed { index, node ->
            node.performTouchInput { down(center); moveBy(Offset(1f, 1f)); up() }
            compose.runOnIdle { assertEquals("Small finger jitter remains a tap", index + 1, clicks) }
            node.performTouchInput { swipe(center, center + Offset(0f, -160f), 400) }
            compose.runOnIdle { assertEquals(0, scroll.value); assertEquals(index + 1, clicks) }
        }
    }

    @Test fun selectionSurfacesStillAllowScrollingAndSelection() {
        lateinit var scroll: ScrollState
        var selected by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(400.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                LiquidGlassSurface(Modifier.size(180.dp, 100.dp).testTag("selection")
                    .selectable(selected) { selected = !selected }) { Text("Selectable content") }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.onNodeWithTag("selection").performTouchInput { click() }
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithTag("selection").performTouchInput { swipe(center, center + Offset(0f, -120f), 400) }
        compose.runOnIdle { assertTrue("Selection does not capture scrolling", scroll.value > 0); assertTrue(selected) }
    }
}
