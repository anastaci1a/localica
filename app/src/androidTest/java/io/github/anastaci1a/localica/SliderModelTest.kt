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

/** #539 red proof: pending contact previews immediately and yields without publishing. */
class SliderModelTest {
    @get:Rule val compose = createComposeRule()
    private var value by mutableFloatStateOf(.2f)
    private val changes = mutableListOf<Float>()
    private var density = 1f
    private lateinit var scroll: ScrollState

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

}
