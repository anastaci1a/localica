package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SplitPillContactTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothActionsPressTheirSharedShellAndCancelWithoutActivation() {
        lateinit var utility: GlassAnchor
        lateinit var options: GlassAnchor
        var clicks = 0
        var reduced by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                utility = rememberGlassAnchor(); options = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                    Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        NavigationUtilityPill(utility, source, Modifier.size(104.dp, 64.dp),
                            onSearch = { clicks++ }, onMore = { clicks++ })
                        BrowseOptionsPill(options) { clicks++ }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        var held: SemanticsNodeInteraction? = null
        try {
            for ((label, anchor) in listOf("Search library" to utility, "More" to utility,
                "Sort" to options, "View options" to options)) {
                val node = compose.onNodeWithContentDescription(label)
                held = node
                node.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(300)
                compose.runOnIdle {
                    assertTrue("$label must reach the shared shell contact", anchor.contact.pressed)
                    assertTrue("$label contact flattens the shared body", anchor.contact.body.value.depth < -.01f)
                    assertEquals("Contact must not activate", 0, clicks)
                }
                node.performTouchInput { moveBy(Offset(width * 3f, 0f)) }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle { assertTrue("Drag cancellation retains pressure until release", anchor.contact.pressed) }
                node.performTouchInput { up() }; held = null
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertFalse("Release clears $label pressure", anchor.contact.pressed)
                    assertEquals("Dragging away cancels activation", 0, clicks)
                }
                compose.mainClock.advanceTimeBy(2200)
                compose.runOnIdle { assertEquals("Shared body settles after $label", 0f, anchor.contact.body.value.depth, .002f) }
            }
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            held = compose.onNodeWithContentDescription("More")
            held.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle {
                assertTrue(utility.contact.pressed)
                assertEquals("Reduced motion keeps the shell undeformed", GlassShape(), utility.contact.body.value)
            }
            compose.onNodeWithContentDescription("More").performTouchInput { up() }; held = null
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Valid release activates once", 1, clicks) }
            val pill = compose.onNodeWithTag("browse-options-trigger").fetchSemanticsNode().boundsInRoot
            assertTrue("Sort/View retains one wide divided pill", pill.width > pill.height * 1.8f)
        } finally {
            try { held?.performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun utilityPressHandsTheSameBodyToAnInterruptedPopupAndBack() {
        lateinit var anchor: GlassAnchor
        var open by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            anchor = rememberGlassAnchor()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                NavigationUtilityPill(anchor, source,
                    Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(32.dp).size(104.dp, 64.dp),
                    onSearch = {}, onMore = { open = true })
                GlassMenu(open, anchor, { open = false }) {
                    ActionMenuItem("Settings", icon = R.drawable.ic_settings, onClick = {})
                    ActionMenuItem("Scan music", icon = R.drawable.ic_folder, onClick = {})
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        var held: SemanticsNodeInteraction? = null
        try {
            val more = compose.onNodeWithContentDescription("More")
            held = more
            more.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle {
                assertFalse("Press feedback precedes popup visibility", anchor.modalPresent)
                assertTrue(anchor.contact.pressed)
                assertTrue(anchor.contact.body.value.depth < -.01f)
            }
            more.performTouchInput { up() }; held = null
            compose.mainClock.advanceTimeBy(176)
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertTrue(anchor.contact.modal)
                assertFalse("Release cannot leave stuck pressure in the popup", anchor.contact.pressed)
            }
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { open = true }
            compose.mainClock.advanceTimeBy(1800)
            compose.onNodeWithText("Settings").assertIsDisplayed()
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeUntil(2500) { !anchor.modalPresent }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.shadowInModal)
                assertFalse(anchor.contact.modal)
                assertFalse(anchor.contact.pressed)
            }
            held = compose.onNodeWithContentDescription("More")
            held.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertTrue("Returned shell still observes contact", anchor.contact.pressed) }
            compose.onNodeWithContentDescription("More").performTouchInput { cancel() }; held = null
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse("Native cancellation clears shell pressure", anchor.contact.pressed)
                assertFalse(open)
            }
        } finally {
            try { held?.performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true }
        }
    }

}
