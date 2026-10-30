package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsLinkMorphTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var popupView: android.view.View

    // Distinctive foreground ink separates text/icon pixels from the neutral
    // material and empty popup. Semantics alone cannot detect a missing fade.
    private fun returningInk(): Float {
        val pixels = capturePopupPixels(compose, compose.onAllNodesWithTag("glass-morph-surface").onLast()) { popupView }
        var ink = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            ink += (color.red - maxOf(color.green, color.blue)).coerceAtLeast(0f)
        }
        return ink
    }

    @Test fun rectangularForegroundFadesDuringReturnAndSurvivesInterruptedReopen() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { ExplicitLegacyPopupTheme {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.Red, onSurfaceVariant = Color.Red)) {
                anchor = rememberGlassAnchor()
                Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
                    SettingsLink("Sort by", "Album / disc / track", anchor = anchor) { open = true }
                }
                GlassMorphPopup(open, anchor, { open = false }) {
                    popupView = androidx.compose.ui.platform.LocalView.current
                    Box(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = true }
        compose.advanceMorphOpening(1500, anchor) { if (::popupView.isInitialized) popupView else null }
        assertTrue(anchor.hidden)
        val expanded = returningInk()
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(320)
        assertTrue("The live trigger is still hidden during this frame", anchor.hidden)
        val early = returningInk()
        assertTrue("The returning modal must already contain trigger ink", early > expanded + 1f)
        compose.mainClock.advanceTimeBy(160)
        val late = returningInk()
        assertTrue("Trigger ink fades in before the final handoff", late > early)
        compose.runOnIdle { open = true }
        compose.advanceMorphOpening(1500, anchor) { if (::popupView.isInitialized) popupView else null }
        assertTrue("Reopening retains one modal-owned trigger", anchor.hidden)
        assertTrue("Expanded content does not retain trigger ink", returningInk() < early)
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(1500)
        assertFalse(anchor.hidden)
        compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
        compose.onNodeWithText("Sort by").assertIsDisplayed()
        compose.onNodeWithText("Album / disc / track").assertIsDisplayed()
    }

    @Test fun nestedChoiceReturnsItsForegroundBeforeRestoringParentTrigger() {
        var parentOpen by mutableStateOf(false)
        var childOpen by mutableStateOf(false)
        var parentView: android.view.View? = null
        lateinit var parent: GlassAnchor
        lateinit var child: GlassAnchor
        compose.setContent { ExplicitLegacyPopupTheme {
            parent = rememberGlassAnchor()
            SettingsLink("Settings", anchor = parent) { parentOpen = true }
            GlassMorphPopup(parentOpen, parent, { parentOpen = false }) {
                parentView = androidx.compose.ui.platform.LocalView.current
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.Red, onSurfaceVariant = Color.Red)) {
                    child = rememberGlassAnchor()
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        SettingsLink("Selector style", "Overlay", anchor = child) { childOpen = true }
                        Spacer(Modifier.height(160.dp))
                    }
                    GlassMorphPopup(childOpen, child, { childOpen = false }) {
                        popupView = androidx.compose.ui.platform.LocalView.current
                        Box(Modifier.fillMaxWidth().height(240.dp))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { parentOpen = true }
        compose.advanceMorphOpening(1500, parent) { parentView }
        compose.runOnIdle { childOpen = true }
        compose.advanceMorphOpening(1500, child) { if (::popupView.isInitialized) popupView else null }
        compose.runOnIdle { childOpen = false }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(child.hidden)
        assertTrue("Nested return retains the parent's foreground palette", returningInk() > 1f)
        compose.mainClock.advanceTimeBy(1500)
        assertFalse(child.hidden)
        assertTrue(parent.hidden)
        compose.onNodeWithText("Selector style").assertIsDisplayed()
        compose.runOnIdle { parentOpen = false }
        compose.mainClock.advanceTimeBy(1500)
        assertFalse(parent.hidden)
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }
}
