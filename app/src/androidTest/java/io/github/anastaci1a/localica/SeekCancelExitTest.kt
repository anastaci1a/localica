package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Release frames from the actual player and shared settings slider callers (#584). */
class SeekCancelExitTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun playerNormalReleaseFliesDownWithoutMissingFrames() = release(player = true, cancel = false)
    @Test fun playerCancelReleaseFadesInPlaceWithoutMissingFrames() = release(player = true, cancel = true)
    @Test fun sliderNormalReleaseFliesDownWithoutMissingFrames() = release(player = false, cancel = false)
    @Test fun sliderCancelReleaseFadesInPlaceWithoutMissingFrames() = release(player = false, cancel = true)

    private fun release(player: Boolean, cancel: Boolean) {
        var value by mutableFloatStateOf(.2f)
        compose.setContent { LocalicaTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (player) PlayerSurface(
                    PlaybackState(connected = true, title = "Exit fixture", positionMs = 20000, durationMs = 100000),
                    true, {}, InterfaceSettings(), remember { Animatable(1f) },
                    {}, {}, {}, {}, {}, {}, visual = PlayerVisual())
                else {
                    val host = rememberSliderCancelHost()
                    CompositionLocalProvider(LocalSliderCancelHost provides host) {
                        InterfaceSlider(value, { value = it }, Modifier.width(300.dp).testTag("slider"))
                        SliderCancelLayer(host, 20.dp)
                    }
                }
            }
        } }
        val slider = if (player) compose.onNodeWithContentDescription("Seek") else compose.onNodeWithTag("slider")
        val target = compose.onNodeWithTag(if (player) "cancel-seek" else "cancel-slider")
        compose.mainClock.autoAdvance = false
        try {
            slider.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
            compose.mainClock.advanceTimeBy(500)
            val at = target.fetchSemanticsNode().boundsInRoot
            if (cancel) {
                val origin = slider.fetchSemanticsNode().boundsInRoot.topLeft
                slider.performTouchInput { moveTo(at.center - origin) }
                compose.mainClock.advanceTimeBy(300)
            }
            slider.performTouchInput { up() }
            var previous = at.top
            // Every presented frame of the early exit must retain the original placement.
            repeat(12) {
                compose.mainClock.advanceTimeByFrame()
                val next = target.fetchSemanticsNode().boundsInRoot
                assertEquals("No horizontal release jump", at.left, next.left, 1f)
                if (cancel) assertEquals("Cancel fades at the release position", at.top, next.top, 1f)
                else assertTrue("Normal exit moves only down: $previous -> ${next.top}", next.top >= previous - 1f)
                previous = next.top
                target.captureToImage() // Force a rendered frame, not only an unpresented layout.
            }
            if (!cancel) assertTrue("Normal release reverses the rise", previous > at.top + 1f)
            compose.mainClock.advanceTimeBy(500)
            target.assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }
}
