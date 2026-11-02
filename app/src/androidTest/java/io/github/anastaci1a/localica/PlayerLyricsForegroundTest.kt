package io.github.anastaci1a.localica

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.ui.PlayerInkTransition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The player foreground follows the lyrics open/close clock instead of switching in one frame. */
@RunWith(AndroidJUnit4::class)
class PlayerLyricsForegroundTest {
    private val white = Color.White
    private val black = Color.Black

    /** Compose interpolates colors perceptually, so compare with its own lerp at the expected fraction. */
    private fun assertMix(from: Color, to: Color, fraction: Float, actual: Color) {
        val expected = lerp(from, to, fraction)
        assertEquals(expected.red, actual.red, .01f); assertEquals(expected.green, actual.green, .01f)
        assertEquals(expected.blue, actual.blue, .01f)
    }

    @Test fun openingKeepsPlayerInkAtStartAndReachesLyricsInkAtEnd() {
        val ink = PlayerInkTransition(white)
        assertMix(white, white, 0f, ink.update(white, 0f, false))
        // The lyrics screen wants dark ink; the animation has just begun.
        assertMix(white, black, 0f, ink.update(black, 0f, true))
        assertMix(white, black, .5f, ink.update(black, .5f, true))
        assertMix(white, black, .75f, ink.update(black, .75f, true))
        assertMix(white, black, 1f, ink.update(black, 1f, true))
    }

    @Test fun midProgressIsNeitherEndpoint() {
        val ink = PlayerInkTransition(white)
        ink.update(white, 0f, false)
        val middle = ink.update(black, .5f, true).let { ink.update(black, .5f, true) }
        assertEquals(1f, middle.red, 1e-3f) // A change at this progress starts here...
        val later = ink.update(black, .75f, true)
        assertMix(white, black, .5f, later) // ...and is halfway to the endpoint after half the remainder.
        assertEquals(false, later == white || later == black)
    }

    @Test fun closingInterpolatesBackToPlayerInk() {
        val ink = PlayerInkTransition(black)
        ink.update(black, 1f, true)
        assertMix(black, black, 0f, ink.update(black, 1f, false))
        // The player's light ink is decided halfway through the close.
        assertMix(black, white, 0f, ink.update(white, .5f, false))
        assertMix(black, white, .5f, ink.update(white, .25f, false))
        assertMix(black, white, 1f, ink.update(white, 0f, false))
    }

    @Test fun lateDecisionContinuesFromDisplayedInkOverTheRemainingProgress() {
        val ink = PlayerInkTransition(white)
        ink.update(white, 0f, true)
        assertMix(white, white, 0f, ink.update(white, .5f, true))
        // The sampled decision flips halfway: no jump, then it eases over the remaining half.
        assertMix(white, black, 0f, ink.update(black, .5f, true))
        assertMix(white, black, .5f, ink.update(black, .75f, true))
        assertMix(white, black, 1f, ink.update(black, 1f, true))
    }

    @Test fun ordinaryChangesOutsideLyricsMotionStayImmediate() {
        val ink = PlayerInkTransition(white)
        assertMix(black, black, 0f, ink.update(black, 0f, false))
        assertMix(white, white, 0f, ink.update(white, 1f, true))
    }

    @Test fun reversalContinuesFromTheDisplayedInk() {
        val ink = PlayerInkTransition(white)
        ink.update(white, 0f, true)
        assertMix(white, black, 0f, ink.update(black, .4f, true))
        val shown = ink.update(black, .7f, true)
        assertMix(white, black, .5f, shown)
        // Close again at the same progress: still the same shade, then toward the closed end.
        assertMix(white, black, .5f, ink.update(black, .7f, false))
        val closing = ink.update(black, .35f, false)
        assertMix(shown, black, .5f, closing)
    }
}
