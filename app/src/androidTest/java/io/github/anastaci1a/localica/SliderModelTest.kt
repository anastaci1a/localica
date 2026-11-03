package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** #539: every slider follows the playback bar's touch and cancel model. */
class SliderModelTest {
    @get:Rule val compose = createComposeRule()
    private var value by mutableFloatStateOf(.2f)
    private val changes = mutableListOf<Float>()
    private val pulses = mutableListOf<ControlHaptic>()
    private var density = 1f
    private lateinit var scroll: ScrollState
    private lateinit var host: SliderCancelHost

    private fun slider() = compose.onNodeWithTag("slider")
    private fun thumb() = compose.onNodeWithTag("settings-slider-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun sliderBounds() = slider().fetchSemanticsNode().boundsInRoot
    /** Root x of a value on the 300 dp slider's 14 dp-inset track. */
    private fun trackX(fraction: Float) = sliderBounds().left + (14f + 272f * fraction) * density
    private fun TouchInjectionScope.track(fraction: Float) = Offset(width * (14f + 272f * fraction) / 300f, centerY)

    @Composable private fun Slider(modifier: Modifier = Modifier) {
        density = LocalDensity.current.density
        InterfaceSlider(value, { value = it; changes += it }, modifier.width(300.dp).testTag("slider"))
    }

    /** A host that draws the cancel target and rises like a popup body. */
    private fun hosted(atBottom: Boolean) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                host = rememberSliderCancelHost()
                CompositionLocalProvider(LocalSliderCancelHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().offset { IntOffset(0, -host.shift.value.roundToInt()) }) {
                            Spacer(Modifier.height(80.dp).then(if (atBottom) Modifier.weight(1f) else Modifier))
                            Slider()
                            if (atBottom) Spacer(Modifier.height(8.dp))
                        }
                        SliderCancelLayer(host, 24.dp)
                    }
                }
            }
        } }
    }

    @Test fun touchDownEasesTheThumbToTheFingerAndATapSetsTheValue() {
        compose.setContent { LocalicaTheme { Box(Modifier.padding(24.dp)) { Slider() } } }
        compose.mainClock.autoAdvance = false
        try {
            slider().performTouchInput { down(track(.8f)) }
            compose.mainClock.advanceTimeByFrame()
            val starting = thumb().center.x
            compose.mainClock.advanceTimeBy(200)
            assertTrue("The thumb moves without waiting for a drag", starting < trackX(.7f))
            assertEquals("Touch-down positions the thumb at the finger", trackX(.8f), thumb().center.x, 3f * density)
            compose.runOnIdle { assertTrue("Nothing commits before release or lock", changes.isEmpty()) }
            slider().performTouchInput { up() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertEquals(listOf(.8f), changes) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun verticalSwipeInAScrollableMenuEasesBackWithoutChanging() {
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(300.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                Slider()
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            slider().performTouchInput { down(track(.8f)) }
            compose.mainClock.advanceTimeBy(120)
            assertTrue("The thumb is on its way to the finger", thumb().center.x > trackX(.3f))
            slider().performTouchInput { moveBy(Offset(0f, -40f), delayMillis = 16); moveBy(Offset(0f, -40f), delayMillis = 16) }
            compose.mainClock.advanceTimeBy(400)
            assertEquals("The thumb eases back to the value", trackX(.2f), thumb().center.x, 3f * density)
            slider().performTouchInput { up() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertTrue(scroll.value > 0); assertTrue(changes.isEmpty()); assertEquals(.2f, value) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cancelBubbleMorphsTheLensBackToTheCircleAndRestoresTheOriginalValue() {
        hosted(atBottom = false)
        val restingTop = sliderBounds().top
        val restingWidth = thumb().width
        slider().performTouchInput { down(track(.2f)); moveTo(track(.8f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(.8f, value, .001f) }
        val cancel = compose.onNodeWithTag("cancel-slider").fetchSemanticsNode().boundsInRoot
        assertEquals("A slider clear of the bubble does not move its menu", restingTop, sliderBounds().top, 1f)
        val lens = thumb().width
        assertTrue("Contact expands the clear lens", lens > restingWidth * 1.4f)
        val origin = sliderBounds().topLeft
        slider().performTouchInput { moveTo(cancel.center - origin) }
        compose.runOnIdle { assertEquals("Hovering X restores the original value", .2f, value, .001f) }
        compose.mainClock.advanceTimeBy(400)
        assertEquals("The lens morphs back into the resting circle", restingWidth, thumb().width, 1.5f * density)
        assertEquals("The circle eases back to the original position", trackX(.2f), thumb().center.x, 3f * density)
        slider().performTouchInput { moveTo(track(.8f)) }
        compose.mainClock.advanceTimeBy(400)
        assertEquals("Leaving X expands the lens again", lens, thumb().width, 1.5f * density)
        assertEquals("Leaving X eases back to the finger", trackX(.8f), thumb().center.x, 3f * density)
        compose.runOnIdle { assertEquals(.8f, value, .001f) }
        slider().performTouchInput { moveTo(cancel.center - origin); up() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Releasing on X keeps the original value", .2f, value, .001f)
            assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
        }
        compose.onNodeWithTag("cancel-slider").assertDoesNotExist()
    }

    @Test fun menuRisesClearOfTheBubbleDuringTheDragAndSettlesAfterRelease() {
        hosted(atBottom = true)
        val resting = sliderBounds()
        slider().performTouchInput { down(track(.2f)); moveTo(track(.6f)) }
        compose.mainClock.advanceTimeBy(600)
        val raised = sliderBounds()
        val cancel = compose.onNodeWithTag("cancel-slider").fetchSemanticsNode().boundsInRoot
        assertTrue("The menu rises: ${resting.top} -> ${raised.top}", raised.top < resting.top - 40f * density)
        assertTrue("The bubble clears the slider", cancel.top >= raised.bottom + 15f * density)
        slider().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        assertEquals("The menu settles back", resting.top, sliderBounds().top, 1f)
        compose.runOnIdle { assertEquals(.6f, value, .001f) }
    }

    @Test fun fullScreenSettingsPageRisesForTheBubble() {
        compose.setContent { LocalicaTheme {
            SettingsPage("Slider page", {}) {
                Spacer(Modifier.weight(1f))
                Slider()
                Spacer(Modifier.height(4.dp))
            }
        } }
        compose.waitForIdle()
        val resting = sliderBounds()
        slider().performTouchInput { down(track(.2f)); moveTo(track(.6f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("cancel-slider").assertExists()
        val cancel: Rect = compose.onNodeWithTag("cancel-slider").fetchSemanticsNode().boundsInRoot
        assertTrue("The page rises clear of the bubble", sliderBounds().bottom <= cancel.top - 15f * density)
        slider().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(resting.top, sliderBounds().top, 1f)
    }
}
