package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

class BackdropGradeTest {
    private fun channel(hex: Int, shift: Int) = (hex ushr shift and 255) / 255.0
    private fun rgb(hex: Int) = doubleArrayOf(channel(hex, 16), channel(hex, 8), channel(hex, 0))
    private fun linear(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
    private fun luminance(c: DoubleArray) = BackdropGrade.luminance(linear(c[0]), linear(c[1]), linear(c[2]))
    private fun lab(c: DoubleArray) = BackdropGrade.labOf(c[0], c[1], c[2])
    private fun chroma(l: DoubleArray) = hypot(l[1], l[2])
    private fun shiftOf(c: DoubleArray, light: Boolean) = BackdropGrade.lightnessShift(c[0], c[1], c[2], light)
    private fun graded(c: DoubleArray, shift: Float) = BackdropGrade.apply(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), shift).map { it.toDouble() }.toDoubleArray()
    private fun contrast(c: DoubleArray, light: Boolean) = BackdropGrade.contrast(luminance(c), light)

    private val covers = listOf(0xD8B4BD, 0x8E7F82, 0x3A0CA3, 0xFFE600, 0xFF2D95, 0x00FF40, 0x808080, 0xF4F4F2, 0x0A0A0C,
        0x7A1F1F, 0x1F6F4A, 0x5AA0E6, 0xC8C0F0, 0x2B2B3A)

    @Test fun neutralPlaceholderNeedsNoGradeBeforeItsFirstFrame() {
        val colors = listOf(NeutralPalette.top, NeutralPalette.middle, NeutralPalette.bottom)
        for (color in colors) {
            val sample = doubleArrayOf(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())
            assertTrue("The owned neutral backing must support cold white ink", contrast(sample, true) >= BackdropGrade.TARGET)
            assertEquals(0f, shiftOf(sample, true), 0f)
        }
        assertEquals(BackdropRoleGrade(true, 0f), PlayerBackgroundPolarityState().grade(null, PlayerVisual()))
    }

    @Test fun readableBackdropsAreUnchanged() {
        // Pink behind dark ink is already 8.5:1; the old curve darkened it to a mauve.
        val pink = rgb(0xD8B4BD)
        assertTrue(contrast(pink, false) > BackdropGrade.TARGET)
        assertEquals(0f, shiftOf(pink, false), 0f)
        assertArrayEquals(pink, graded(pink, 0f), 1e-6)
        // The same covers behind the other ink that already reads.
        for (hex in listOf(0x0A0A0C, 0x2B2B3A, 0x3A0CA3)) assertEquals(0f, shiftOf(rgb(hex), true), 0f)
        for (hex in listOf(0xF4F4F2, 0xFFE600, 0x00FF40)) assertEquals(0f, shiftOf(rgb(hex), false), 0f)
    }

    @Test fun lightnessOnlyMovesTheContrastIncreasingWay() {
        for (hex in covers) for (light in listOf(false, true)) {
            val c = rgb(hex)
            val shift = shiftOf(c, light)
            if (light) assertTrue("$hex light ink must not lighten: $shift", shift <= 0f)
            else assertTrue("$hex dark ink must not darken: $shift", shift >= 0f)
            val out = graded(c, shift)
            assertTrue("$hex contrast never drops", contrast(out, light) >= contrast(c, light) - 1e-5)
            if (shift != 0f) assertEquals("$hex lightness moved by the shift", lab(c)[0] + shift, lab(out)[0], .01)
        }
    }

    @Test fun minimumContrastIsMetAndNothingMoreIsSpent() {
        for (hex in covers) for (light in listOf(false, true)) {
            val c = rgb(hex)
            val shift = shiftOf(c, light)
            if (shift == 0f) continue
            val out = graded(c, shift)
            assertTrue("$hex ${contrast(out, light)}", contrast(out, light) >= BackdropGrade.TARGET)
            // Minimum: half of the shift would fall short of the target.
            val half = graded(c, shift / 2)
            assertTrue("$hex half shift should not suffice", contrast(half, light) < BackdropGrade.TARGET)
            // Not overshooting: within a percent of the target.
            assertTrue("$hex overshoot ${contrast(out, light)}", contrast(out, light) < BackdropGrade.TARGET * 1.02)
        }
    }

    @Test fun chromaAndHueArePreservedUnlessGamutClips() {
        for (hex in covers) for (light in listOf(false, true)) {
            val c = rgb(hex)
            val shift = shiftOf(c, light)
            if (shift == 0f) continue
            val before = lab(c); val after = lab(graded(c, shift))
            val inGamutAfter = BackdropGrade.fromLab(after[0], before[1], before[2]).all { it in -1e-6..1 + 1e-6 }
            if (inGamutAfter) assertEquals("$hex chroma", chroma(before), chroma(after), .004)
            // Chroma never grows, and the hue angle holds wherever there is chroma to speak of.
            assertTrue("$hex chroma never grows", chroma(after) <= chroma(before) + .004)
            if (chroma(before) > .04 && chroma(after) > .04) {
                val delta = abs(Math.atan2(before[2], before[1]) - Math.atan2(after[2], after[1]))
                assertTrue("$hex hue drift $delta", delta < .05)
            }
        }
        // The old curve took the pink mauve and stripped its chroma; the lift keeps a mid pink's chroma.
        val dusty = rgb(0x8E4A5E)
        val shift = shiftOf(dusty, false)
        assertTrue(shift > 0f)
        assertEquals(chroma(lab(dusty)), chroma(lab(graded(dusty, shift))), .004)
    }

    @Test fun gradingIsContinuousInTheMeasuredColorAndTheShift() {
        // Sweep grey and a saturated ramp: a small change of the input never makes a large change of the shift or result.
        for (light in listOf(false, true)) {
            // The shift is Lipschitz in the measured color: it never changes faster than the OKLab lightness it is measured from.
            var color = doubleArrayOf(0.0, 0.0, 0.0); var last = shiftOf(color, light)
            for (step in 1..1000) {
                val v = step / 1000.0
                val next = doubleArrayOf(v, v * .5, v * .8); val now = shiftOf(next, light)
                val moved = abs(lab(next)[0] - lab(color)[0])
                assertTrue("jump at $v: $last -> $now", abs(now - last) <= 1.5 * moved + 1e-3)
                last = now; color = next
            }
        }
        val c = rgb(0x3A0CA3); var previous = graded(c, 0f)
        for (step in 1..200) {
            val now = graded(c, step * .002f)
            for (i in 0..2) assertTrue(abs(now[i] - previous[i]) < .01)
            previous = now
        }
    }

    @Test fun inkFlipIsSeamlessBecauseTheTieColorNeedsNoGrade() {
        // The ink decision's tie luminance (sqrt(.0525) - .05) meets the target for both inks within a hair.
        val y = kotlin.math.sqrt(.0525) - .05
        val g = (y * 12.92).let { 1.055 * y.pow(1 / 2.4) - .055 }
        val grey = doubleArrayOf(g, g, g)
        assertTrue(abs(shiftOf(grey, false)) < .01f && abs(shiftOf(grey, true)) < .01f)
    }

    @Test fun packedPixelsMatchTheFloatGradeAndKeepAlpha() {
        val c = rgb(0x2B2B3A)
        val shift = shiftOf(c, false)
        assertTrue(shift > 0f)
        val out = BackdropGrade.applyPixel(0x80_2B2B3A.toInt(), shift)
        assertEquals(0x80, out ushr 24)
        val expected = graded(c, shift)
        for (i in 0..2) assertEquals(expected[i], (out ushr (16 - 8 * i) and 255) / 255.0, .003)
        assertEquals(0x80_2B2B3A.toInt(), BackdropGrade.applyPixel(0x80_2B2B3A.toInt(), 0f))
    }

    @Test fun toneCarriesTheShiftAndZeroShiftIsIdentity() {
        assertTrue(GlassTone().identity)
        assertFalse(GlassTone(shift = .1f).identity)
        val c = androidx.compose.ui.graphics.Color(0x2B, 0x2B, 0x3A)
        assertEquals(c, GlassTone().color(c))
        assertTrue(GlassTone(shift = .2f).color(c).red > c.red)
    }
}
