package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.PopupTakeover
import io.github.anastaci1a.localica.ui.engine.ShadowPlane
import io.github.anastaci1a.localica.ui.engine.windowRetained
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupSiblingOwnershipTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun disposingAClosedSiblingDoesNotRetireTheOpenPresentation() = overlap(false)
    @Test fun heldExitDoesNotRetireANewerPresentationOnTheSameTrigger() = overlap(true)
    @Test fun disposingAWaitingSiblingNeverFollowsTheHeldPresentation() = overlap(true, true)

    private fun fieldHost(anchor: GlassAnchor): Any {
        val node = requireNotNull(anchor.surfaceNode)
        val owner = node.javaClass.getDeclaredField("owner\$delegate").apply { isAccessible = true }.get(node) as State<*>
        return (owner.value as ShadowPlane).host.window
    }

    private fun overlap(heldExit: Boolean, disposeWaiting: Boolean = false) {
        var aOpen by mutableStateOf(false)
        var bOpen by mutableStateOf(false)
        var aMounted by mutableStateOf(true)
        var bMounted by mutableStateOf(true)
        lateinit var anchor: GlassAnchor
        var aView: View? = null
        var bView: View? = null
        var aHome: GlassPopupHome? = null
        var dismissA: (() -> Unit)? = null
        val child = GlassPopupFlight()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    ListSearchPill("Shared trigger", {}, Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp), anchor)
                    if (aMounted) GlassMorphPopup(aOpen, anchor, { aOpen = false }, GlassPlacement.Search) {
                        aView = LocalView.current
                        aHome = LocalGlassPopupHome.current
                        dismissA = LocalPopupDismiss.current
                        Text("Popup A", Modifier.height(220.dp))
                    }
                    if (bMounted) GlassMorphPopup(bOpen, anchor, { bOpen = false }, GlassPlacement.Search) {
                        bView = LocalView.current
                        Text("Popup B", Modifier.height(180.dp))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            var a: GlassPopupFlight? = null
            if (heldExit) {
                compose.runOnUiThread { aOpen = true }
                compose.advanceMorphOpening(2000, anchor) { aView }
                compose.onNodeWithText("Popup A").assertIsDisplayed()
                compose.runOnUiThread {
                    a = requireNotNull(anchor.popup)
                    requireNotNull(aHome).flights.add(child)
                    requireNotNull(dismissA).invoke()
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread {
                    assertFalse(aOpen)
                    assertTrue(requireNotNull(aView).isAttachedToWindow)
                    assertEquals("Physical presentation stays held after logical Hide", 1f, requireNotNull(a).progress.value, 0f)
                    assertEquals("Hide takes effect before child handoff", 0f, requireNotNull(a).progress.targetValue, 0f)
                }
            }
            compose.runOnUiThread { bOpen = true }
            if (heldExit) {
                compose.advanceMorphOpening(160, anchor) { bView }
                lateinit var waiting: GlassPopupFlight
                compose.runOnUiThread {
                    waiting = requireNotNull(anchor.popup)
                    assertNotSame(a, waiting)
                    assertEquals(PopupTakeover.Admitting, waiting.takeover)
                    assertEquals(1f, requireNotNull(a).progress.value, 0f)
                    assertTrue(requireNotNull(aView).isAttachedToWindow)
                    assertSame("B waits without following A's physical surface", requireNotNull(aView).rootView, fieldHost(anchor))
                    if (disposeWaiting) bMounted = false
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread {
                    if (disposeWaiting) {
                        assertEquals(PopupTakeover.Closed, waiting.takeover)
                        assertNull(anchor.popup)
                        assertSame(requireNotNull(aView).rootView, fieldHost(anchor))
                        assertEquals(1f, requireNotNull(a).progress.value, 0f)
                    }
                    child.handedOff = true
                }
                if (disposeWaiting) {
                    compose.advanceMorphOpening(2000, anchor) { aView }
                    compose.runOnUiThread {
                        assertEquals(PopupTakeover.Closed, requireNotNull(a).takeover)
                        assertFalse(requireNotNull(aView).isAttachedToWindow)
                        assertFalse(requireNotNull(bView).isAttachedToWindow)
                        assertNull(anchor.popup)
                        assertFalse(requireNotNull(anchor.surfaceNode).windowRetained())
                        assertSame("The surface returns home after the waiting collector is cancelled", anchor.shadowPlane?.host?.window, fieldHost(anchor))
                    }
                    compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
                    return
                }
            }
            compose.advanceMorphOpening(2000, anchor) { bView }
            compose.onNodeWithText("Popup B").assertIsDisplayed()
            lateinit var b: GlassPopupFlight
            compose.runOnUiThread {
                b = requireNotNull(anchor.popup)
                assertNotSame(a, b)
                assertSame("Trigger expansion belongs to the current presentation", b.progress, anchor.contact.expansion)
                assertEquals(PopupTakeover.InWindow, b.takeover)
                assertSame("The new presentation owns the physical source field", requireNotNull(bView).rootView, fieldHost(anchor))
                if (heldExit) child.handedOff = true else aMounted = false
            }
            compose.advanceMorphOpening(2000, anchor) { bView }
            compose.runOnUiThread {
                if (heldExit) {
                    assertEquals(PopupTakeover.Closed, requireNotNull(a).takeover)
                    assertFalse(requireNotNull(aView).isAttachedToWindow)
                }
                assertTrue(bOpen)
                assertSame("Old exit/disposal cannot clear a newer admission", b, anchor.popup)
                assertTrue(anchor.hidden && anchor.modalPresent)
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                assertTrue(requireNotNull(bView).isAttachedToWindow)
                assertSame("The old return cannot steal the new window field", requireNotNull(bView).rootView, fieldHost(anchor))
                aMounted = false
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Popup B").assertIsDisplayed()
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            compose.runOnUiThread { assertSame(b, anchor.popup) }
        } finally {
            compose.runOnUiThread { child.handedOff = true; aOpen = false; bOpen = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
}
