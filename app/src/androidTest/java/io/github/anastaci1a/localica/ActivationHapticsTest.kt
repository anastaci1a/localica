package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivationHapticsTest {
    @get:Rule val compose = createComposeRule()
    private val pulses = mutableListOf<ControlHaptic>()
    private val sink = ControlHaptics { pulses += it }
    private var button by mutableIntStateOf(0)
    private var enabled by mutableStateOf(true)
    private var playing by mutableStateOf(false)
    private var clicks = 0
    private val domainAction: () -> Unit = { clicks++ }

    private fun buttons() {
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalControlHaptics provides sink) {
            Box(Modifier.width(300.dp).height(100.dp)) {
                when (button) {
                    0 -> ActionTextButton(domainAction, enabled = enabled) { Text("Action") }
                    1 -> ActionIconButton(domainAction, enabled = enabled) { Text("Action") }
                    2 -> GlassActionButton(R.drawable.ic_plus, "Action", enabled = enabled, onClick = domainAction)
                    3 -> TransportButton(TransportGlyph.PlayPause, playing, "Action", domainAction,
                        Modifier.size(64.dp), 32.dp, Color.Black, enabled, false)
                    4 -> SettingsLink("Action", enabled = enabled, onClick = domainAction)
                    5 -> ActionIcon(R.drawable.ic_plus, "Action", enabled = enabled, quiet = true, onClick = domainAction)
                    6 -> ActionIcon(R.drawable.ic_plus, "Action", enabled = enabled, glass = true, onClick = domainAction)
                    7 -> ActionIcon(R.drawable.ic_plus, "Action", enabled = enabled, glass = false, onClick = domainAction)
                    8 -> ActionMenuItem("Action", icon = R.drawable.ic_more, enabled = enabled, onClick = domainAction)
                }
            }
        } } }
    }

    @Test fun sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation() {
        buttons()
        repeat(9) { index ->
            compose.runOnIdle { button = index; clicks = 0; pulses.clear() }
            val node = compose.onNode(hasClickAction())
            node.performTouchInput { down(center) }
            compose.runOnIdle { assertEquals(emptyList<ControlHaptic>(), pulses) }
            node.performTouchInput { up() }
            node.performClick()
            node.performTouchInput { repeat(5) { click() } }
            compose.runOnIdle {
                assertEquals("Button $index activates seven times", 7, clicks)
                assertEquals("Button $index must not layer wrapper pulses", List(7) { ControlHaptic.Weak }, pulses)
            }
        }
    }

    @Test fun disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback() {
        buttons()
        repeat(9) { index ->
            compose.runOnIdle { button = index; enabled = false; clicks = 0; pulses.clear() }
            val node = compose.onNode(hasClickAction())
            node.performTouchInput { click() }
            node.performClick()
            compose.runOnIdle { enabled = true }
            node.performTouchInput { down(center); cancel() }
            node.performTouchInput { down(center); moveBy(Offset(0f, -160f)); up() }
            compose.runOnIdle {
                assertEquals("Button $index must not activate", 0, clicks)
                assertEquals("Button $index must remain silent", emptyList<ControlHaptic>(), pulses)
            }
        }
    }

    @Test fun programmaticStateAndDomainCallbacksRemainSilent() {
        buttons()
        compose.runOnIdle { button = 3; playing = true; domainAction() }
        compose.waitForIdle()
        compose.runOnIdle { playing = false; enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { enabled = true }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(emptyList<ControlHaptic>(), pulses)
        }
    }

    @Test fun returnToPositionActionEmitsOnceWithoutPulsingForDirectionChanges() {
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalControlHaptics provides sink) {
            ReturnToPositionButton(playing, "Return", onClick = domainAction)
        } } }
        compose.runOnIdle { playing = true }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(listOf(ControlHaptic.Weak), pulses) }
    }

    @Test fun playerActionsIgnoreEmptySlotsAndDoNotAddActivationPulseToLongClicks() {
        var holds = 0
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalControlHaptics provides sink) {
            Box(Modifier.width(300.dp).height(96.dp)) {
                PlayerActionStrip(PlayerActionSettings(), { it.label }, { clicks++ }, { holds++ }, shuffle = playing)
            }
        } } }
        compose.onNodeWithTag("player-slot-0:0").performTouchInput { click() }
        compose.onNodeWithTag("player-slot-0:0").performClick()
        compose.onNodeWithTag("player-slot-0:4").performClick()
        compose.onNodeWithTag("player-slot-0:0").performTouchInput { longClick() }
        compose.onNodeWithTag("player-slot-0:0").performTouchInput { down(center); cancel() }
        compose.runOnIdle { playing = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, clicks); assertEquals(1, holds)
            // Framework-owned long-press feedback is unchanged; no custom activation is added.
            assertEquals(List(2) { ControlHaptic.Weak }, pulses)
        }
    }

    @Test fun filterBubblesPulseOncePerActivationAndNotForSelectionMotionOrHolds() {
        var selected by mutableStateOf(setOf("A"))
        var holds = 0
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalControlHaptics provides sink) {
            Box(Modifier.width(300.dp)) {
                OrderedGlassBubbles(listOf("A", "B"), selected, { it }, false, enabled = enabled,
                    role = Role.Tab, onSelect = { selected = setOf(it); clicks++ }, onLongClick = { holds++ })
            }
        } } }
        compose.onNodeWithTag("B").performTouchInput { click() }
        compose.onNodeWithTag("A").performClick()
        compose.onNodeWithTag("A").performTouchInput { repeat(4) { click() } }
        compose.onNodeWithTag("A").performTouchInput { longClick() }
        compose.onNodeWithTag("A").performTouchInput { down(center); cancel() }
        compose.onNodeWithTag("A").performTouchInput { down(center); moveBy(Offset(100f, 0f)); up() }
        compose.runOnIdle { selected = setOf("B"); enabled = false }
        compose.waitForIdle()
        compose.onNodeWithTag("B").performTouchInput { click() }
        compose.onNodeWithTag("B").performClick()
        compose.runOnIdle {
            assertEquals(6, clicks); assertEquals(1, holds)
            assertEquals(List(6) { ControlHaptic.Weak }, pulses)
        }
    }
    @Test fun filledActionRetainsMaterialGeometryInkAndButtonSemantics() {
        var dark by mutableStateOf(false)
        var nativeInk: Color? = null
        var sharedInk: Color? = null
        compose.setContent { LocalicaTheme { GlassPanelTheme(dark) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.width(280.dp).background(Color.Magenta).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button({}, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("filled-reference"), enabled = enabled) {
                        nativeInk = androidx.compose.material3.LocalContentColor.current
                        Text("Filled action")
                    }
                    ActionButton({}, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("filled-shared"), enabled = enabled) {
                        sharedInk = androidx.compose.material3.LocalContentColor.current
                        Text("Filled action")
                    }
                }
            }
        } } }
        for (darkMode in listOf(false, true)) for (available in listOf(true, false)) {
            compose.runOnIdle { dark = darkMode; enabled = available }
            val reference = compose.onNodeWithTag("filled-reference")
            val shared = compose.onNodeWithTag("filled-shared")
            for (node in listOf(reference, shared)) {
                node.assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                if (available) node.assertIsEnabled() else node.assertIsNotEnabled()
                node.assertHeightIsAtLeast(48.dp)
            }
            compose.onAllNodes(hasClickAction() and
                (hasTestTag("filled-shared") or hasAnyAncestor(hasTestTag("filled-shared"))),
                useUnmergedTree = true).assertCountEquals(1)
            val nativeBounds = reference.getUnclippedBoundsInRoot()
            val sharedBounds = shared.getUnclippedBoundsInRoot()
            assertEquals((nativeBounds.right - nativeBounds.left).value, (sharedBounds.right - sharedBounds.left).value, .001f)
            assertEquals((nativeBounds.bottom - nativeBounds.top).value, (sharedBounds.bottom - sharedBounds.top).value, .001f)
            compose.runOnIdle { assertEquals(requireNotNull(nativeInk), requireNotNull(sharedInk)) }
            val nativePixels = reference.captureToImage().toPixelMap()
            val sharedPixels = shared.captureToImage().toPixelMap()
            assertEquals(nativePixels.width, sharedPixels.width)
            assertEquals(nativePixels.height, sharedPixels.height)
            // Sample the filled face inside its top/bottom center, away from the label.
            for (y in listOf(4, nativePixels.height - 5)) {
                val expected = nativePixels[nativePixels.width / 2, y]
                val actual = sharedPixels[sharedPixels.width / 2, y]
                assertTrue("The native reference has a filled face, not the parent background", expected != Color.Magenta)
                assertEquals(expected.red, actual.red, .01f)
                assertEquals(expected.green, actual.green, .01f)
                assertEquals(expected.blue, actual.blue, .01f)
                assertEquals(expected.alpha, actual.alpha, .01f)
            }
        }
    }

    @Test fun filledActionUsesCurrentCallbackAndKeepsDisabledCancelledAndScrollContactsSilent() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var version by mutableIntStateOf(0)
        val events = mutableListOf<String>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { events += it.name }) {
                scroll = androidx.compose.foundation.rememberScrollState()
                Column(Modifier.height(450.dp).controlVerticalScroll(scroll)) {
                    Spacer(Modifier.height(100.dp))
                    val versionAtComposition = version
                    ActionButton({ events += "callback-$versionAtComposition" },
                        Modifier.testTag("filled-action"), enabled) { Text("Filled action") }
                    Spacer(Modifier.height(900.dp))
                }
            }
        } }
        val node = compose.onNodeWithTag("filled-action")
        node.performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(events.isEmpty()); version = 1 }
        node.performTouchInput { up() }
        node.performClick()
        compose.runOnIdle {
            assertEquals(listOf("Weak", "callback-1", "Weak", "callback-1"), events)
            events.clear()
        }
        node.performTouchInput { down(center) }
        compose.runOnIdle { enabled = false }
        node.performTouchInput { up(); click() }
        node.performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()); enabled = true }
        node.performTouchInput { down(center); cancel() }
        node.performTouchInput { down(center); moveBy(Offset(0f, -160f)); up() }
        compose.runOnIdle { assertTrue(events.isEmpty()); scroll.dispatchRawDelta(-scroll.value.toFloat()) }
        node.performTouchInput { swipe(center, center + Offset(0f, -160f), 120) }
        compose.runOnIdle {
            assertTrue("Fast intent reaches the parent", scroll.value > 0)
            assertTrue("Parent scrolling cannot activate or pulse", events.isEmpty())
            scroll.dispatchRawDelta(-scroll.value.toFloat())
        }
        node.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("Weak", "callback-1"), events) }
    }

}
