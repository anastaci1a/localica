package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.anastaci1a.localica.ui.engine.PhaseBinding
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Frame sequences for the light/dark polarity change during the lyrics open/close animation (#564).
 * The foreground ink and the material polarity (glass tint, frost, shading) come from one resolution,
 * so each sequence checks both: monotonic interpolation and no single-frame jump.
 */
class PlayerPolarityEaseTest {
    private val white = Color.White
    private val black = Color.Black
    private val frames = 40

    private class Frame(val progress: Float, val ink: Color, val light: Float)

    /** Runs the transition one frame at a time. [progressAt] is the lyrics clock, [inkAt] the sampled decision. */
    private fun run(initial: Color, open: (Int) -> Boolean, progressAt: (Int) -> Float, inkAt: (Int) -> Color, count: Int = frames + 1): List<Frame> {
        val ink = PhaseBinding(initial.luminance().let { if (it > .5f) 1f else 0f })
        return (0 until count).map { i ->
            val step = ink.resolve(if (inkAt(i) == white) 1f else 0f, progressAt(i), if (open(i)) 1f else 0f).also(ink::present)
            Frame(progressAt(i), androidx.compose.ui.graphics.lerp(black, white, step.value), step.value)
        }
    }

    private fun opening(i: Int) = i / frames.toFloat()
    private fun closing(i: Int) = 1f - i / frames.toFloat()

    private fun assertNoJump(sequence: List<Frame>, largestStep: Float) {
        sequence.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("material jump at frame $i: ${a.light} -> ${b.light}", abs(b.light - a.light) <= largestStep)
        }
    }

    private fun assertForegroundFollowsMaterial(sequence: List<Frame>) = sequence.forEachIndexed { i, f ->
        val expected = androidx.compose.ui.graphics.lerp(black, white, f.light)
        assertEquals("frame $i red", expected.red, f.ink.red, .02f)
    }

    @Test fun openingToDarkInkEasesBothMonotonicallyFromTheFlipToTheEnd() {
        for (flip in listOf(0, 5, 14, 30, 38)) {
            val sequence = run(white, { true }, ::opening, { if (it < flip) white else black })
            if (flip > 0) assertEquals("no change before the decision", 1f, sequence[flip - 1].light, 0f)
            // The frame the decision arrives continues from the displayed polarity.
            assertEquals(1f, sequence[flip].light, 1e-3f)
            assertEquals(0f, sequence.last().light, 0f)
            assertEquals(black, sequence.last().ink)
            sequence.zipWithNext().forEach { (a, b) ->
                assertTrue(b.light <= a.light + 1e-6f); assertTrue(b.ink.luminance() <= a.ink.luminance() + 1e-4f)
            }
            if (flip <= 30) assertTrue("eases through intermediate values for flip=$flip", sequence.count { it.light in .05f..0.95f } >= (frames - flip) / 2)
            assertNoJump(sequence, 1.6f / (frames - flip).coerceAtLeast(1)) // C1 ease: peak 1.5x the mean
            assertForegroundFollowsMaterial(sequence)
        }
    }

    @Test fun closingToLightInkEasesBackMonotonically() {
        for (flip in listOf(0, 8, 20, 33)) {
            val sequence = run(black, { false }, ::closing, { if (it < flip) black else white })
            // Starts fully open (progress 1), where the dark ink was already settled.
            assertEquals(0f, sequence.first().light, 0f)
            assertEquals(1f, sequence.last().light, 0f)
            sequence.zipWithNext().forEach { (a, b) ->
                assertTrue(b.light >= a.light - 1e-6f); assertTrue(b.ink.luminance() >= a.ink.luminance() - 1e-4f)
            }
            assertNoJump(sequence, 1.6f / (frames - flip).coerceAtLeast(1)) // C1 ease: peak 1.5x the mean
            assertForegroundFollowsMaterial(sequence)
        }
    }

    @Test fun reversingTheLyricsAnimationMidEaseContinuesFromWhatIsShown() {
        // Open, flip to dark at frame 8, reverse to closing at frame 20 (the decision stays dark).
        val sequence = run(white, { it < 20 }, { if (it < 20) opening(it) else (opening(20) - (it - 20) / frames.toFloat()).coerceAtLeast(0f) },
            { if (it < 8) white else black }, count = 50)
        assertNoJump(sequence, .12f)
        sequence.zipWithNext().forEach { (a, b) -> assertTrue("never drifts back toward the old ink", b.light <= a.light + 1e-6f) }
        assertEquals(0f, sequence.last().light, 0f)
        assertForegroundFollowsMaterial(sequence)
    }

    @Test fun aDecisionThatReversesMidEaseTurnsAroundWithoutJumping() {
        val sequence = run(white, { true }, ::opening, { if (it in 10 until 22) black else white })
        assertNoJump(sequence, .12f)
        val turn = sequence.indexOfFirst { it.light < .999f }
        val low = sequence.drop(turn).minOf { it.light }
        assertTrue("the ease heads toward dark first", low < .95f)
        assertEquals(1f, sequence.last().light, 0f)
        // Down while dark is wanted, then up again after it reverses.
        val bottom = sequence.indexOfFirst { it.light == low }
        sequence.subList(turn, bottom + 1).zipWithNext().forEach { (a, b) -> assertTrue(b.light <= a.light + 1e-6f) }
        sequence.subList(bottom, sequence.size).zipWithNext().forEach { (a, b) -> assertTrue(b.light >= a.light - 1e-6f) }
    }

    @Test fun reducedMotionSnapsBecauseTheLyricsClockSnaps() {
        // Reduced Motion gives the lyrics clock a zero duration: it jumps from 0 to 1 and the ink goes with it.
        val sequence = run(white, { true }, { if (it < 3) 0f else 1f }, { if (it < 2) white else black }, count = 6)
        assertTrue("only the two endpoints appear", sequence.all { it.light == 0f || it.light == 1f })
        assertEquals(0f, sequence.last().light, 0f)
        val reverse = run(black, { false }, { if (it < 3) 1f else 0f }, { if (it < 2) black else white }, count = 6)
        assertTrue(reverse.all { it.light == 0f || it.light == 1f }); assertEquals(1f, reverse.last().light, 0f)
    }

    /** Prints the frame strip kept as release evidence; also asserts it is a smooth ramp. */
    @Test fun frameStripShowsOneSmoothRamp() {
        fun table(title: String, sequence: List<Frame>) {
            println("STRIP $title")
            println("STRIP frame,lyricsProgress,foregroundRed,materialLightMix")
            sequence.forEachIndexed { i, f -> println("STRIP %d,%.3f,%.3f,%.3f".format(java.util.Locale.ROOT, i, f.progress, f.ink.red, f.light)) }
        }
        val open = run(white, { true }, ::opening, { if (it < 14) white else black })
        table("opening to the lyrics theme (dark ink decided at frame 14)", open)
        val close = run(black, { false }, ::closing, { if (it < 8) black else white })
        table("closing back to the player theme (light ink decided at frame 8)", close)
        assertNoJump(open, .1f); assertNoJump(close, .1f)
    }

    @Test fun outsideTheLyricsAnimationAChangeStaysImmediate() {
        val sequence = run(white, { false }, { 0f }, { if (it < 3) white else black }, count = 6)
        assertEquals(1f, sequence[2].light, 0f); assertEquals(0f, sequence[3].light, 0f)
        val open = run(white, { true }, { 1f }, { if (it < 3) white else black }, count = 6)
        assertEquals(1f, open[2].light, 0f); assertEquals(0f, open[3].light, 0f)
    }
}
