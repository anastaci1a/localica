package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The reveal is a draw-phase value: the same pixels as before, without recomposing the cell every frame. */
class ArtworkRevealTest {
    @get:Rule val compose = createComposeRule()
    private val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
    private var image by mutableStateOf<Bitmap?>(null)
    private val recompositions = AtomicInteger()
    private var reduced by mutableStateOf(false)

    private fun content() = compose.setContent {
        CompositionLocalProvider(LocalReducedMotion provides reduced) {
            LocalicaTheme {
                ArtworkImage(image?.asImageBitmap(), false,
                    Modifier.size(64.dp).testTag("reveal").composed { recompositions.incrementAndGet(); this }, RectangleShape)
            }
        }
    }

    /** Sample a corner away from the placeholder icon. */
    private fun corner() = compose.onNodeWithTag("reveal").captureToImage().toPixelMap().let { it[2, 2] }

    @Test fun revealEndpointsAndMonotonicProgressMatchTheAnimatedAlphaBlend() {
        compose.mainClock.autoAdvance = false
        content()
        compose.mainClock.advanceTimeBy(100)
        val base = corner()
        assertTrue("Placeholder before the cover arrives: $base", !(base.red > .9f && base.green < .1f))
        compose.runOnIdle { image = red }
        var previous = base.green
        val seen = mutableSetOf<Int>()
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            val pixel = corner()
            assertTrue("Cover coverage only increases: ${pixel.green} after $previous", pixel.green <= previous + .01f)
            assertTrue(pixel.red >= base.red - .01f)
            seen += (pixel.green * 255).toInt()
            previous = pixel.green
        }
        assertTrue("A genuine intermediate blend is drawn: $seen", seen.size >= 3)
        val end = corner()
        assertEquals(1f, end.red, .01f); assertEquals(0f, end.green, .01f); assertEquals(0f, end.blue, .01f)
    }

    @Test fun animatingTheRevealDoesNotRecomposeEachFrame() {
        compose.mainClock.autoAdvance = false
        content()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { image = red }
        compose.mainClock.advanceTimeBy(16)
        val started = recompositions.get()
        compose.mainClock.advanceTimeBy(330)
        val during = recompositions.get() - started
        assertEquals("Reveal frames and completion must not recompose the cell", 0, during)
    }

    @Test fun reducedMotionRevealsInstantlyAndFullyOpaque() {
        reduced = true
        compose.mainClock.autoAdvance = false
        content()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { image = red }
        compose.mainClock.advanceTimeBy(64)
        val pixel = corner()
        assertEquals(1f, pixel.red, .01f); assertEquals(0f, pixel.green, .01f)
    }

    /** A reduced reveal rests at its target; turning reduced motion off afterwards never fades the art again. */
    @Test fun reducedRevealStaysOpaqueWhenReducedMotionTurnsOff() {
        reduced = true
        compose.mainClock.autoAdvance = false
        content()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { image = red }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { reduced = false }
        repeat(25) {
            compose.mainClock.advanceTimeBy(16)
            val pixel = corner()
            assertEquals("Frame $it after reduced motion turned off", 1f, pixel.red, .01f)
            assertEquals(0f, pixel.green, .01f)
        }
    }

    /** Reduced motion turning on mid-reveal rests the reveal at once, like the earlier zero-length reveal. */
    @Test fun reducedMotionMidRevealRestsAtOnce() {
        compose.mainClock.autoAdvance = false
        content()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { image = red }
        compose.mainClock.advanceTimeBy(80)
        assertTrue("Mid reveal before reduced motion", corner().green > .02f)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeBy(16)
        val snapped = corner()
        assertEquals(1f, snapped.red, .01f); assertEquals(0f, snapped.green, .01f)
        compose.runOnIdle { reduced = false }
        repeat(20) {
            compose.mainClock.advanceTimeBy(16)
            assertEquals("Frame $it after reduced motion turned off", 0f, corner().green, .01f)
        }
    }

    /** The text and action wait for the whole Reveal profile (300 ms) and are released together when it rests. */
    @Test fun textAndActionReleaseTogetherWhenTheRevealRests() {
        val text = ArtworkTextReveal()
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalicaTheme {
                ArtworkImage(image?.asImageBitmap(), loading, Modifier.size(64.dp).testTag("reveal"), RectangleShape, textReveal = text)
            }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertTrue(text.waiting); assertEquals(0f, text.menuAlpha(1f), 0f); assertFalse(text.menuShown(1f)); assertTrue(text.menuShown(.5f)) }
        compose.runOnIdle { image = red; loading = false }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertTrue("Text still waits mid reveal", text.waiting)
            assertTrue("Action follows the same reveal: ${text.menuAlpha(1f)}", text.menuAlpha(1f) in .02f..0.98f)
            assertEquals(text.textAlpha, text.menuAlpha(1f), 0f); assertTrue("Shown while the action fades in", text.menuShown(1f))
        }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertFalse("Text is released at rest", text.waiting)
            assertEquals(1f, text.textAlpha, 0f); assertEquals(1f, text.menuAlpha(1f), 0f); assertTrue(text.menuShown(1f))
        }
    }

    @Test fun alreadyPresentArtworkIsDrawnOpaqueOnTheFirstFrame() {
        image = red
        content()
        compose.waitForIdle()
        val pixel = corner()
        assertEquals(1f, pixel.red, .01f); assertEquals(0f, pixel.green, .01f)
    }
}
