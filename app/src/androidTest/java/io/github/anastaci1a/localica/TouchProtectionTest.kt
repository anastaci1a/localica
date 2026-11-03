package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #538: one scroll-vs-tap rule for every control, drawer and the player. */
class TouchProtectionTest {
    @get:Rule val compose = createComposeRule()
    private var activations = 0
    private var slop = 0f
    private lateinit var scroll: ScrollState

    /** Every shared control type, each reporting any activation or value change. */
    private enum class Control(val tag: String) {
        GlassButton("glass-button"), PopupOpener("popup-opener"), TextButton("text-button"),
        SwitchRow("settings-switch"), Slider("slider"), Choices("choice-option-1"), FilterChoice("filter-choice"),
        Checkbox("checkbox"),
    }

    @Composable private fun Content(control: Control) {
        val activate = { activations++ }
        when (control) {
            Control.GlassButton -> Box(Modifier.testTag(control.tag)) { GlassActionButton(R.drawable.ic_plus, "Glass") { activate() } }
            Control.PopupOpener -> Box(Modifier.testTag(control.tag)) { SettingsLink("Selector style") { activate() } }
            Control.TextButton -> ActionTextButton({ activate() }, Modifier.testTag(control.tag)) { Text("Text") }
            Control.SwitchRow -> BooleanPreference("Switch", false) { activate() }
            Control.Slider -> InterfaceSlider(.5f, { activate() }, Modifier.testTag(control.tag))
            Control.Choices -> GlassChoices(listOf("First", "Second"), 0, horizontal = true) { activate() }
            Control.FilterChoice -> Box(Modifier.testTag(control.tag)) { GlassFilterChoice(false, { activate() }, "Filter", true) }
            Control.Checkbox -> InterfaceCheckbox(false, { activate() }, Modifier.testTag(control.tag))
        }
    }

    /** A short scrollable menu that keeps the control visible at both extremes. */
    private fun scrollable(control: Control) {
        activations = 0
        compose.setContent { LocalicaTheme {
            slop = LocalViewConfiguration.current.touchSlop
            scroll = rememberScrollState()
            Column(Modifier.width(320.dp).height(220.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(110.dp))
                Content(control)
                Spacer(Modifier.height(110.dp))
            }
        } }
    }

    private fun node(control: Control) = compose.onNodeWithTag(control.tag, useUnmergedTree = true)

    /** A prompt vertical swipe that stays inside the control. */
    private fun shortSwipe(control: Control, down: Boolean) = node(control).performTouchInput {
        down(center)
        moveBy(Offset(0f, (if (down) 1f else -1f) * (slop + 8f)), delayMillis = 16)
        up()
    }

    private fun longSwipe(control: Control, down: Boolean) = node(control).performTouchInput {
        down(center)
        repeat(4) { moveBy(Offset(0f, (if (down) 1f else -1f) * 30f), delayMillis = 16) }
        up()
    }

    /** Controls whose vertical drag itself changes a value. */
    private val dragControls = listOf(Control.SwitchRow, Control.Slider)

    /** Start toward the edge, then reverse: only a parent that took the contact could scroll. */
    private fun towardEdgeThenBack(control: Control, down: Boolean) = node(control).performTouchInput {
        val sign = if (down) 1f else -1f
        down(center)
        moveBy(Offset(0f, sign * (slop + 8f)), delayMillis = 16)
        repeat(3) { moveBy(Offset(0f, -sign * 40f), delayMillis = 16) }
        up()
    }

    @Test fun everyControlKeepsContactTowardAScrollEdgeAndScrollsAwayFromIt() {
        for (control in Control.entries) {
            scrollable(control)
            compose.runOnIdle { assertTrue("$control fixture scrolls", scroll.maxValue > 0) }
            towardEdgeThenBack(control, down = true)
            compose.runOnIdle {
                assertEquals("$control: a swipe down at the top belongs to the control", 0, scroll.value)
                activations = 0
            }
            longSwipe(control, down = false)
            compose.runOnIdle {
                assertTrue("$control: swiping up at the top scrolls", scroll.value > 0)
                assertEquals("$control: scrolling never activates", 0, activations)
                scroll.dispatchRawDelta(scroll.maxValue.toFloat())
            }
            compose.waitForIdle()
            towardEdgeThenBack(control, down = false)
            compose.runOnIdle {
                assertEquals("$control: a swipe up at the bottom belongs to the control", scroll.maxValue, scroll.value)
                activations = 0
            }
            longSwipe(control, down = true)
            compose.runOnIdle {
                assertTrue("$control: swiping down at the bottom scrolls", scroll.value < scroll.maxValue)
                assertEquals("$control: scrolling never activates", 0, activations)
            }
        }
    }

    @Test fun dragControlsActivateOnVerticalSwipesTowardAnEdge() {
        for (control in dragControls) {
            scrollable(control)
            shortSwipe(control, down = true)
            compose.runOnIdle {
                assertEquals(0, scroll.value)
                assertTrue("$control: swiping down at the top activates", activations > 0)
                scroll.dispatchRawDelta(scroll.maxValue.toFloat())
                activations = 0
            }
            compose.waitForIdle()
            shortSwipe(control, down = false)
            compose.runOnIdle {
                assertEquals(scroll.maxValue, scroll.value)
                assertTrue("$control: swiping up at the bottom activates", activations > 0)
            }
        }
    }

    @Test fun nonScrollableMenuNeverBlocksVerticalActivation() {
        for (control in dragControls) {
            activations = 0
            compose.setContent { LocalicaTheme {
                slop = LocalViewConfiguration.current.touchSlop
                scroll = rememberScrollState()
                // A fitting body registers as a scroll parent but cannot move either way.
                Column(Modifier.width(320.dp).height(400.dp).controlVerticalScroll(scroll)) {
                    Spacer(Modifier.height(80.dp))
                    Content(control)
                }
            } }
            for (down in listOf(true, false)) {
                shortSwipe(control, down)
                compose.runOnIdle {
                    assertTrue("$control activates on a ${if (down) "down" else "up"} swipe", activations > 0)
                    activations = 0
                }
            }
        }
    }

    @Test fun holdingStillLocksEveryControlTypeAgainstScrolling() {
        for (control in Control.entries) {
            scrollable(control)
            node(control).performTouchInput {
                down(center)
                advanceEventTime(250L + 20)
                moveBy(Offset(0f, -40f), delayMillis = 16)
                moveBy(Offset(0f, -60f), delayMillis = 16)
                up()
            }
            compose.runOnIdle { assertEquals("$control keeps a held contact", 0, scroll.value) }
        }
    }

    @Test fun horizontalSlideLocksSlidersAndSwitchesBeforeTheHold() {
        for (control in listOf(Control.Slider, Control.SwitchRow)) {
            scrollable(control)
            val target = if (control == Control.SwitchRow) compose.onNodeWithTag("settings-switch", useUnmergedTree = true)
                else node(control)
            target.performTouchInput {
                down(center)
                moveBy(Offset(slop + 10f, 2f), delayMillis = 16)
                moveBy(Offset(0f, -120f), delayMillis = 16)
                up()
            }
            compose.runOnIdle {
                assertEquals("$control: a locked slide cannot scroll", 0, scroll.value)
                assertTrue("$control: the slide adjusts", activations > 0)
            }
        }
    }

    @Test fun pressVisiblyLetsGoWhenScrollingTakesOverAndHeldPressStays() {
        lateinit var contact: GlassContact
        var clicks = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.width(320.dp).height(300.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                contact = rememberGlassContact()
                Box(Modifier.size(120.dp).testTag("press").glassPress(contact).ownButtonDrag()
                    .clickable(interactionSource = null, indication = null) { clicks++ })
                Spacer(Modifier.height(900.dp))
            }
        } }
        val press = compose.onNodeWithTag("press")
        press.performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(contact.pressed) }
        // The finger stays on the control while the list moves under it.
        press.performTouchInput { moveBy(Offset(0f, -30f), delayMillis = 16); moveBy(Offset(0f, -10f), delayMillis = 16) }
        compose.runOnIdle {
            assertTrue("The list took the contact", scroll.value > 0)
            assertFalse("The control lets go once scrolling owns the contact", contact.pressed)
        }
        press.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, clicks)
            assertEquals("The let-go settles back to rest", 1f, contact.body.value.scaleX(), .001f)
            scroll.dispatchRawDelta(-scroll.value.toFloat())
        }
        press.performTouchInput { down(center); advanceEventTime(250L + 20); moveBy(Offset(0f, -40f), delayMillis = 16) }
        compose.runOnIdle { assertEquals(0, scroll.value); assertTrue("A held press stays pressed while dragged", contact.pressed) }
        press.performTouchInput { up() }
        compose.runOnIdle { assertFalse(contact.pressed); assertEquals("A drag is never a tap", 0, clicks) }
    }

    @Test fun swipingDownFromQueueHeaderControlsClosesTheDrawer() {
        val progress = Animatable(1f)
        val settled = mutableListOf<Boolean>()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            QueueSurface(progress, InterfaceSettings(), { settled += it }) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Box(Modifier.testTag("queue-pill")) { GlassActionButton(R.drawable.ic_plus, "Queue selector") {} }
                    ActionIconButton({}, Modifier.testTag("queue-more")) { Text("More") }
                }
                Box(Modifier.weight(1f).fillMaxWidth())
            }
        } } }
        for (tag in listOf("queue-pill", "queue-more")) {
            compose.runOnIdle { kotlinx.coroutines.runBlocking { progress.snapTo(1f) }; settled.clear() }
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
                swipe(center, center + Offset(0f, 600f), durationMillis = 200)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("$tag: a downward swipe closes the queue", listOf(false), settled)
                assertEquals(0f, progress.value, .001f)
            }
        }
    }

    private fun player(onExpanded: (Boolean) -> Unit, seeks: MutableList<Long>) {
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(PlaybackState(connected = true, title = "Touch fixture", positionMs = 20_000, durationMs = 100_000),
                true, onExpanded, InterfaceSettings(), remember { Animatable(1f) },
                {}, { seeks += it }, {}, {}, {}, {}, visual = PlayerVisual())
        } } }
    }

    @Test fun verticalSwipesStartingOnPlayerControlsMoveThePlayer() {
        for (control in listOf("Next", "Seek")) {
            val expanded = mutableListOf<Boolean>()
            val seeks = mutableListOf<Long>()
            player({ expanded += it }, seeks)
            compose.onNodeWithContentDescription(control).performTouchInput {
                swipe(center, center + Offset(0f, 700f), durationMillis = 200)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("$control: swiping down collapses the player", listOf(false), expanded)
                assertTrue("$control: a vertical drag never seeks", seeks.isEmpty())
            }
        }
    }

    @Test fun playbackBarTapSeeksAndHeldOrSlidingContactStaysWithTheBar() {
        val expanded = mutableListOf<Boolean>()
        val seeks = mutableListOf<Long>()
        player({ expanded += it }, seeks)
        val seek = compose.onNodeWithContentDescription("Seek")
        seek.performTouchInput { click(Offset(width * .75f, centerY)) }
        compose.runOnIdle { assertEquals(.75f * 100_000f, seeks.single().toFloat(), 3_000f) }
        compose.onNodeWithTag("cancel-seek").assertDoesNotExist()
        // Holding locks: the later vertical drag cannot move the player and reaches X.
        seek.performTouchInput { down(Offset(width * .3f, centerY)) }
        compose.mainClock.advanceTimeBy(250L + 64)
        compose.onNodeWithTag("cancel-seek").assertExists()
        val bounds = seek.fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(400)
        val cancel = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot
        seek.performTouchInput { moveTo(cancel.center - bounds.topLeft, delayMillis = 160); up() }
        compose.runOnIdle { assertTrue(expanded.isEmpty()); assertEquals(1, seeks.size) }
        // A horizontal slide locks before the hold, and keeps the later vertical travel.
        seek.performTouchInput {
            down(Offset(width * .3f, centerY))
            moveBy(Offset(width * .2f, 0f), delayMillis = 16)
            moveBy(Offset(0f, 300f), delayMillis = 16)
            moveBy(Offset(0f, -300f), delayMillis = 16)
            up()
        }
        compose.runOnIdle {
            assertTrue("A locked scrub never moves the player", expanded.isEmpty())
            assertEquals(2, seeks.size)
            assertEquals(.5f * 100_000f, seeks.last().toFloat(), 3_000f)
        }
    }

    @Test fun barEasesToTheTouchAndBackWhenAVerticalSwipeTakesOver() {
        var value by mutableFloatStateOf(20f)
        val changes = mutableListOf<Float>()
        var commits = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.width(300.dp).height(300.dp).background(Color.Black).controlVerticalScroll(scroll)) {
                    Spacer(Modifier.height(100.dp))
                    PlayerSeekBar(value, { value = it; changes += it }, 0f..100f, { commits++ }, true,
                        Modifier.width(300.dp).height(40.dp).testTag("bar"), liveValue = 20f)
                    Spacer(Modifier.height(900.dp))
                }
            }
        } }
        val bar = compose.onNodeWithTag("bar")
        fun painted(): Float {
            val pixels = bar.captureToImage().toPixelMap()
            val y = pixels.height / 2
            val track = (0 until pixels.width).filter { pixels[it, y].red > .12f }
            val fill = track.filter { pixels[it, y].red > .5f }
            return (fill.last() - track.first()).toFloat() / (track.last() - track.first())
        }
        compose.mainClock.autoAdvance = false
        try {
            bar.performTouchInput { down(Offset(width * .8f, centerY)) }
            compose.mainClock.advanceTimeBy(96)
            val easing = painted()
            assertTrue("Touch-down is recognised at once and eases toward the finger: $easing", easing > .25f && easing < .78f)
            compose.runOnIdle { assertTrue("Nothing publishes before the contact locks", changes.isEmpty()) }
            bar.performTouchInput { moveBy(Offset(0f, -40f), delayMillis = 16); moveBy(Offset(0f, -40f), delayMillis = 16) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertTrue("The swipe scrolls the parent", scroll.value > 0) }
            assertEquals("The bar eases back to playback", .2f, painted(), .04f)
            bar.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertTrue(changes.isEmpty()); assertEquals(0, commits) }
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("bar").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(20f, 0f..100f, 0)))
    }
}
