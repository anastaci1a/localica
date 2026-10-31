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
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun alreadyPresentArtworkIsDrawnOpaqueOnTheFirstFrame() {
        image = red
        content()
        compose.waitForIdle()
        val pixel = corner()
        assertEquals(1f, pixel.red, .01f); assertEquals(0f, pixel.green, .01f)
    }
}
