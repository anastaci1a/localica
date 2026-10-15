package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.QueueRepeat
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ControlStateMotionTest {
    @get:Rule val compose = createComposeRule()
    private var presentationStage by mutableIntStateOf(0)
    private lateinit var sceneView: android.view.View

    @Test fun oppositeDirectionsShareProgressAndRapidReversalContinuesFromVisibleValue() {
        var selected by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        var forward = 0f
        var reverse = 1f
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                val a by animateControlStateFloat(if (selected) 1f else 0f, "Forward")
                val b by animateControlStateFloat(if (selected) 0f else 1f, "Reverse")
                // Exercise rendered clients rather than an empty composition.
                Row {
                    Box(Modifier.size(32.dp).background(Color.White.copy(alpha = a)))
                    Box(Modifier.size(32.dp).background(Color.White.copy(alpha = b)))
                }
                SideEffect { forward = a; reverse = b }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = true }
        var previous = 0f
        repeat(8) {
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle {
                assertEquals("Both directions have identical elapsed progress", 1f, forward + reverse, .001f)
                assertTrue(forward >= previous)
                previous = forward
                android.util.Log.i("ControlStateMotionTest", "t=${compose.mainClock.currentTime} forward=$forward reverse=$reverse")
            }
        }
        compose.runOnIdle {
            assertTrue("Rendered fade must make intermediate progress", forward > 0f)
            assertTrue("Normal fade remains visible beyond 250 ms", forward < 1f)
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { assertEquals(1f, forward, 0f); selected = false }
        compose.mainClock.advanceTimeBy(128)
        var interrupted = 0f
        compose.runOnIdle { interrupted = forward; selected = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("Reversal cannot jump to an endpoint", forward in .05f.. .95f)
            assertEquals("Reversal begins at the current appearance", interrupted, forward, .12f)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1f, forward, 0f); reduced = true; selected = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("Reduced motion snaps", 0f, forward, 0f) }
    }

    @Test fun reducedMotionSnapsAvailabilityAndInheritedInkDoesNotAcquireASecondFade() {
        var enabled by mutableStateOf(true)
        var ink by mutableStateOf(Color.White)
        var shown = Color.Unspecified
        var reduced by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                val color = controlStateColor(enabled, ink, ink.copy(alpha = .38f), "Availability")
                SideEffect { shown = color }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(.38f, shown.alpha, .001f); enabled = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, shown.alpha, .001f); reduced = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { ink = Color.Black }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(Color.Black, shown) }
    }

    @Test fun applyFadesWhiteAndGraySymmetricallyWhileDisabledClicksStopImmediately() {
        var enabled by mutableStateOf(true)
        var calls = 0
        compose.setContent { LocalicaTheme { GlassPanelTheme(true) { CaptureScene {
            Box(Modifier.background(Color.Black).testTag("apply-ink")) {
                ActionTextButton({ calls++ }, enabled = enabled) { Text("Apply") }
            }
        } } } }
        val full = ink("apply-ink")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeBy(144)
        val fadingOut = ink("apply-ink")
        compose.onNodeWithText("Apply").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.mainClock.advanceTimeBy(400)
        val dim = ink("apply-ink")
        assertTrue("Disabled appearance has one 38% dimming owner", dim / full in .35.. .41)
        assertTrue(fadingOut > dim && fadingOut < full)
        compose.runOnIdle { enabled = true }
        compose.mainClock.advanceTimeBy(144)
        val fadingIn = ink("apply-ink")
        assertEquals("Matching elapsed time has equal forward/reverse progress",
            (full - fadingOut) / (full - dim), (fadingIn - dim) / (full - dim), .08)
        compose.onNodeWithText("Apply").assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls) }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(full, ink("apply-ink"), full * .01)
    }

    @Test fun playerModeAndBadgeFadesKeepOneLiveLabelAndFixedTargets() {
        var shuffle by mutableStateOf(false)
        var repeat by mutableStateOf(QueueRepeat.All)
        val slots = PlayerActionSettings(slots = mapOf(
            ActionSlot(0, 0) to PlayerAction.Shuffle, ActionSlot(0, 1) to PlayerAction.Repeat), centered = false)
        compose.setContent { LocalicaTheme { CaptureScene {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(Modifier.size(160.dp, 48.dp).background(Color.Black)) {
                    PlayerActionStrip(slots, { if (it == PlayerAction.Repeat) "Repeat $repeat" else "Shuffle $shuffle" },
                        {}, {}, shuffle = shuffle, repeat = repeat)
                }
            }
        } } }
        val shuffleTag = "player-slot-0:0"
        val repeatTag = "player-slot-0:1"
        val off = ink(shuffleTag)
        val badgeAbsent = ink(repeatTag)
        val bounds = compose.onNodeWithTag(repeatTag).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shuffle = true; repeat = QueueRepeat.One }
        compose.mainClock.advanceTimeBy(144)
        compose.onNodeWithTag(shuffleTag).assertIsSelected()
        compose.onAllNodesWithContentDescription("Repeat One").assertCountEquals(1)
        val entering = ink(shuffleTag)
        val badgeEntering = ink(repeatTag)
        compose.mainClock.advanceTimeBy(400)
        val on = ink(shuffleTag)
        val badgePresent = ink(repeatTag)
        assertTrue(entering > off && entering < on)
        assertTrue(badgeEntering > badgeAbsent && badgeEntering < badgePresent)
        compose.runOnIdle { shuffle = false; repeat = QueueRepeat.All }
        compose.mainClock.advanceTimeBy(144)
        compose.onNodeWithTag(shuffleTag).assertIsNotSelected()
        compose.onAllNodesWithContentDescription("Repeat All").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Repeat One").assertCountEquals(0)
        assertEquals(bounds, compose.onNodeWithTag(repeatTag).fetchSemanticsNode().boundsInRoot)
        assertEquals((entering - off) / (on - off), (on - ink(shuffleTag)) / (on - off), .06)
        val badgeLeaving = ink(repeatTag)
        assertTrue("Outgoing badge remains drawn during fade", badgeLeaving > badgeAbsent && badgeLeaving < badgePresent)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(off, ink(shuffleTag), on * .01)
        assertEquals(badgeAbsent, ink(repeatTag), badgePresent * .01)
    }

    @Test fun sharedDisabledControlsRejectTouchBeforeTheirVisualFadeSettles() {
        var enabled by mutableStateOf(true)
        var calls = 0
        compose.setContent { LocalicaTheme { Column(Modifier.width(300.dp)) {
            ActionIcon(R.drawable.ic_shuffle, "Glass icon", enabled = enabled) { calls++ }
            ActionIcon(R.drawable.ic_shuffle, "Quiet icon", enabled = enabled, quiet = true) { calls++ }
            ActionIcon(R.drawable.ic_shuffle, "Plain icon", enabled = enabled, glass = false) { calls++ }
            GlassActionButton(R.drawable.ic_shuffle, "Glass action", enabled = enabled) { calls++ }
            ActionMenuItem("Menu action", enabled) { calls++ }
            SettingsLink("Settings action", enabled = enabled) { calls++ }
            InterfaceCheckbox(false, { calls++ }, Modifier.testTag("checkbox"), enabled)
            GlassFilterChoice(false, { calls++ }, "Filter action", enabled)
            TransportButton(TransportGlyph.PlayPause, false, "Transport", { calls++ },
                Modifier.size(48.dp), 24.dp, Color.Black, enabled, false)
        } } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeBy(32)
        listOf("Glass icon", "Quiet icon", "Plain icon", "Transport").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled().performTouchInput { click() }
        }
        listOf("Glass action", "Menu action", "Settings action", "Filter action").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithTag("checkbox").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Composable
    private fun CaptureScene(content: @Composable () -> Unit) {
        sceneView = LocalView.current
        Column(Modifier.background(Color.Black).testTag("fade-scene")) {
            // Outside the controls: frame identity must not affect measured ink.
            PresentationMarker { presentationStage }
            content()
        }
    }

    /** Integrated white ink from an independently identified compositor frame. */
    private fun ink(tag: String): Double {
        compose.runOnIdle { presentationStage++ }
        val root = compose.onNodeWithTag("fade-scene")
        val pixels = capturePresentedPopupPixels(compose, root, presentationStage) { sceneView }
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.translate(-origin)
        var sum = 0.0
        for (y in kotlin.math.floor(bounds.top).toInt() until kotlin.math.ceil(bounds.bottom).toInt())
            for (x in kotlin.math.floor(bounds.left).toInt() until kotlin.math.ceil(bounds.right).toInt())
                sum += pixels[x, y].red
        return sum
    }
}
