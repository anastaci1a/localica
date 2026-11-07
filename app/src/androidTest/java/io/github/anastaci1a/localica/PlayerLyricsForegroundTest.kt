package io.github.anastaci1a.localica

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.ui.engine.PhaseBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The player foreground follows the lyrics open/close phase (#600 U08.02a binding) instead of switching in one frame. */
@RunWith(AndroidJUnit4::class)
class PlayerLyricsForegroundTest {
    private val white = Color.White
    private val black = Color.Black

    /** The shown ink is Compose's perceptual mix of the endpoint inks at the binding's light value. */
    private fun PhaseBinding.update(target: Color, progress: Float, open: Boolean): Color =
        lerp(black, white, resolve(if (target == white) 1f else 0f, progress, if (open) 1f else 0f).also(::present).value)
    private fun assertMix(from: Color, to: Color, fraction: Float, actual: Color) {
        val expected = lerp(from, to, fraction)
        assertEquals(expected.red, actual.red, .01f); assertEquals(expected.green, actual.green, .01f)
        assertEquals(expected.blue, actual.blue, .01f)
    }
    /** The C1 ease from rest: halfway in travel is halfway in ink. */
    private fun smooth(u: Float) = u * u * (3f - 2f * u)

    @Test fun openingKeepsPlayerInkAtStartAndReachesLyricsInkAtEnd() {
        val ink = PhaseBinding(1f)
        assertMix(white, white, 0f, ink.update(white, 0f, false))
        // The lyrics screen wants dark ink; the animation has just begun.
        assertMix(white, black, 0f, ink.update(black, 0f, true))
        assertMix(white, black, .5f, ink.update(black, .5f, true))
        assertMix(white, black, smooth(.75f), ink.update(black, .75f, true))
        assertMix(white, black, 1f, ink.update(black, 1f, true))
    }

    @Test fun resolvingIsPureUntilCommitted() {
        val ink = PhaseBinding(1f)
        ink.update(white, 0f, false)
        // Repeated resolution during recomposition changes nothing; only present does.
        val first = ink.resolve(0f, .5f, 1f)
        assertEquals(first, ink.resolve(0f, .5f, 1f))
        assertEquals(first, ink.resolve(0f, .5f, 1f).also { ink.resolve(1f, .1f, 1f) })
        ink.present(first)
        assertMix(white, black, smooth(.5f), lerp(black, white, ink.resolve(0f, .75f, 1f).value))
    }

    @Test fun midProgressIsNeitherEndpoint() {
        val ink = PhaseBinding(1f)
        ink.update(white, 0f, false)
        val middle = ink.update(black, .5f, true).let { ink.update(black, .5f, true) }
        assertEquals(1f, middle.red, 1e-3f) // A change at this progress starts here...
        val later = ink.update(black, .75f, true)
        assertMix(white, black, .5f, later) // ...and is halfway to the endpoint after half the remainder.
        assertEquals(false, later == white || later == black)
    }

    @Test fun closingInterpolatesBackToPlayerInk() {
        val ink = PhaseBinding(0f)
        ink.update(black, 1f, true)
        assertMix(black, black, 0f, ink.update(black, 1f, false))
        // The player's light ink is decided halfway through the close.
        assertMix(black, white, 0f, ink.update(white, .5f, false))
        assertMix(black, white, .5f, ink.update(white, .25f, false))
        assertMix(black, white, 1f, ink.update(white, 0f, false))
    }

    @Test fun lateDecisionContinuesFromDisplayedInkOverTheRemainingProgress() {
        val ink = PhaseBinding(1f)
        ink.update(white, 0f, true)
        assertMix(white, white, 0f, ink.update(white, .5f, true))
        // The sampled decision flips halfway: no jump, then it eases over the remaining half.
        assertMix(white, black, 0f, ink.update(black, .5f, true))
        assertMix(white, black, .5f, ink.update(black, .75f, true))
        assertMix(white, black, 1f, ink.update(black, 1f, true))
    }

    @Test fun ordinaryChangesOutsideLyricsMotionStayImmediate() {
        val ink = PhaseBinding(1f)
        assertMix(black, black, 0f, ink.update(black, 0f, false))
        assertMix(white, white, 0f, ink.update(white, 1f, true))
    }

    @Test fun reversalContinuesFromTheDisplayedInkWithoutTurningBack() {
        val ink = PhaseBinding(1f)
        ink.update(white, 0f, true)
        assertMix(white, black, 0f, ink.update(black, .4f, true))
        val shown = ink.update(black, .7f, true)
        assertMix(white, black, .5f, shown)
        // Close again at the same progress: still the same shade, then on toward the dark target, never back.
        assertMix(white, black, .5f, ink.update(black, .7f, false))
        val closing = ink.update(black, .35f, false)
        assertTrue("closing keeps moving toward the target", closing.red < shown.red)
        assertMix(black, black, 0f, ink.update(black, 0f, false))
    }
}
