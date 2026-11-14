package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchDismissalLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refusedDismissalDuringOpeningRetiresTheOldFlightAndKeepsEditing() {
        var visible by mutableStateOf(false)
        var dismissals = 0
        var edits = 0
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        val samples = mutableListOf<SearchMorphSample>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false,
                LocalSearchMorphObserver provides { samples += it }) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    anchor = rememberGlassAnchor()
                    Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp).height(48.dp)
                        .glassAnchor(anchor))
                    GlassMorphPopup(visible, anchor, { dismissals++ }, GlassPlacement.Search) {
                        val view = LocalView.current
                        SideEffect { popupView = view }
                        Column(Modifier.fillMaxWidth().height(220.dp)) {
                            ActionTextButton({ edits++ }) { Text("Keep editing") }
                        }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        try {
            compose.runOnUiThread { visible = true }
            compose.advanceMorphOpening(160, anchor) { popupView }
            compose.waitUntil(5000) { popupView?.hasWindowFocus() == true }
            compose.runOnUiThread {
                assertTrue("Dismiss an admitted, unfinished opening", anchor.hidden &&
                    anchor.contact.expansion.value > 0f && anchor.contact.expansion.value < 1f)
            }
            val originalView = popupView
            // pressBack waits for a window-change event; a refused dismissal keeps its window.
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .pressKeyCode(android.view.KeyEvent.KEYCODE_BACK))
            compose.waitUntil(5000) { dismissals == 1 }
            val start = compose.mainClock.currentTime
            while (compose.mainClock.currentTime - start < 2000) frame()
            compose.runOnUiThread {
                assertTrue("The caller deliberately keeps its editor visible", visible)
                assertSame("Refused dismissal keeps the same native window", originalView, popupView)
                assertEquals(1, dismissals)
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                assertTrue("The search publishes placed geometry", samples.isNotEmpty())
                assertNull("No opening owner can wait for a flight that dismissal retired", samples.last().flight)
                assertEquals("Opening vacancy settles after the replacement owner", 1f, anchor.searchSlot.fraction, 0f)
            }
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            compose.onNodeWithText("Keep editing").performClick()
            compose.runOnIdle { assertEquals(1, edits) }
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
    @Test fun dismissalWithoutAChildHandoffClearsSemanticsOnTheFirstFrame() = firstHideFrame(false)
    @Test fun dismissalWithAChildHandoffClearsSemanticsOnTheFirstFrame() = firstHideFrame(true)

    private fun firstHideFrame(handedOff: Boolean) {
        var visible by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var home: GlassPopupHome
        var dismiss: (() -> Unit)? = null
        var popupView: View? = null
        var dismissals = 0
        val child = GlassPopupFlight().also { it.handedOff = handedOff }
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                anchor = rememberGlassAnchor()
                Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).size(240.dp, 48.dp).glassAnchor(anchor))
                GlassMorphPopup(visible, anchor, { visible = false; dismissals++ }, GlassPlacement.Search) {
                    home = checkNotNull(LocalGlassPopupHome.current)
                    dismiss = LocalPopupDismiss.current
                    popupView = LocalView.current
                    Text("Dismissal semantics witness", Modifier.height(220.dp))
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { visible = true }
            compose.advanceMorphOpening(2000, anchor) { popupView }
            compose.onNodeWithText("Dismissal semantics witness").assertExists()
            compose.runOnUiThread {
                home.flights.add(child)
                assertEquals(handedOff, home.handedOff)
                checkNotNull(dismiss).invoke()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Dismissal semantics witness").assertDoesNotExist()
            compose.runOnUiThread {
                assertEquals(1, dismissals)
                assertEquals("Child retirement cannot delay Hide", handedOff, home.handedOff)
            }
        } finally {
            compose.runOnUiThread { home.flights.remove(child); visible = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

}
