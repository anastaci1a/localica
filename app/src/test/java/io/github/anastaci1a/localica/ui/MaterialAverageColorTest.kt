package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class MaterialAverageColorTest {
    private val neutral = MaterialAverageStyle(0f, GlassTone())
    private fun blend(vararg contributors: Pair<Color, Float>, style: MaterialAverageStyle = neutral) =
        materialAverageColor(contributors.toList(), style)
    private fun color(expected: Color, actual: Color, tolerance: Float = .005f) {
        // sRGB Color packs channels to bytes, independently of the arithmetic precision.
        assertEquals(expected.red, actual.red, tolerance)
        assertEquals(expected.green, actual.green, tolerance)
        assertEquals(expected.blue, actual.blue, tolerance)
        assertEquals(expected.alpha, actual.alpha, tolerance)
    }

    @Test fun endpointsEmptyAndZeroWeightsRetainOnlyAdmittedContributions() {
        assertEquals(Color.Red, blend(Color.Red to 1f, Color.Blue to 0f))
        assertEquals(Color.Blue, blend(Color.Red to 0f, Color.Blue to 1f))
        assertEquals(Color.Transparent, blend())
        assertEquals(Color.Transparent, blend(Color.Red to 0f, Color.Transparent to 1f))
        assertEquals(Color.Red, blend(Color.Red to 1f, Color.White to 0f, Color.Black to -0f))
    }

    @Test fun equalAndUnequalMixesHaveIndependentExpectedChannels() {
        color(Color(.5f, 0f, .5f), blend(Color.Red to .5f, Color.Blue to .5f))
        color(Color(.25f, 0f, .75f), blend(Color.Red to .25f, Color.Blue to .75f))
    }

    @Test fun premultiplicationAndPartialTotalWeightPreserveOutputAlpha() {
        val halfRed = Color(1f, 0f, 0f, .5f)
        // Packed half alpha is128/255: expected values use that actual input rather than idealizing it.
        val redAlpha = halfRed.alpha * .25f
        val blueAlpha = .5f
        val alpha = redAlpha + blueAlpha
        color(Color(redAlpha / alpha, 0f, blueAlpha / alpha, alpha), blend(halfRed to .25f, Color.Blue to .5f))
        color(Color(1f, 0f, 0f, .25f), blend(Color.Red to .25f))
        color(Color.Blue, blend(Color.Transparent to .5f, Color.Blue to 1f))
        // Total weights above one are permitted when premultiplied resulting alpha is representable.
        val quarter = Color(0f, 0f, 1f, .25f)
        color(Color(0f, 0f, 1f, quarter.alpha * 2f), blend(quarter to 2f))
    }

    @Test fun tintAndLightDarkCompressionFollowIndependentClosedFormValues() {
        val inputs = arrayOf(Color.Black to .5f, Color.White to .5f)
        // Shader whiteTint=.5 takes neutral .5 to2/3, then applies tone exactly once.
        color(Color(2f / 3f, 2f / 3f, 2f / 3f), blend(*inputs, style = MaterialAverageStyle(.5f, GlassTone())))
        color(Color(13f / 18f, 13f / 18f, 13f / 18f),
            blend(*inputs, style = MaterialAverageStyle(.5f, GlassTone(.5f, 2f, false))))
        color(Color(4f / 9f, 4f / 9f, 4f / 9f),
            blend(*inputs, style = MaterialAverageStyle(.5f, GlassTone(.5f, 2f, true))))
        color(Color(5f / 6f, 5f / 6f, 5f / 6f),
            blend(*inputs, style = MaterialAverageStyle(.5f, GlassTone(.5f, 1f, false))))
        color(Color(1f / 3f, 1f / 3f, 1f / 3f),
            blend(*inputs, style = MaterialAverageStyle(.5f, GlassTone(.5f, 1f, true))))
    }

    @Test fun coloredLuminanceUsesAllThreeShaderCoefficientsAndFractionalWeight() {
        val tint = .3
        // Blend(.25 red,.75 blue), then independently simplify the tint equation algebraically.
        val luma = .2126 * .25 + .0722 * .75
        val amount = tint * (1.0 - luma) / (1.0 - tint * luma)
        fun expected(channel: Double): Float {
            val lifted = channel + (1.0 - channel) * amount
            return (lifted - .2 * lifted.pow(1.5)).toFloat()
        }
        color(Color(expected(.25), expected(0.0), expected(.75)),
            blend(Color.Red to .25f, Color.Blue to .75f,
                style = MaterialAverageStyle(tint.toFloat(), GlassTone(.2f, 1.5f, true))))
        // Green-dominated mix independently exercises the0.7152 coefficient.
        val greenLuma = .7152
        val greenTint = tint * (1.0 - greenLuma) / (1.0 - tint * greenLuma)
        color(Color(greenTint.toFloat(), 1f, greenTint.toFloat()),
            blend(Color.Green to 1f, style = MaterialAverageStyle(tint.toFloat(), GlassTone())))
    }

    @Test fun toneAfterBlendDiffersFromIncorrectPerContributorTone() {
        val result = blend(Color.Black to .5f, Color.White to .5f,
            style = MaterialAverageStyle(0f, GlassTone(.5f, 2f, false)))
        color(Color(.625f, .625f, .625f), result)
        val incorrectlyMappedBeforeBlend = .75f
        assertTrue(incorrectlyMappedBeforeBlend - result.red > .1f)
    }

    @Test fun fullWhiteTintAndTransparentResultsStayBounded() {
        color(Color.White, blend(Color.Black to 1f, style = MaterialAverageStyle(1f, GlassTone())))
        color(Color.White, blend(Color.White to 1f, style = MaterialAverageStyle(1f, GlassTone())))
        assertEquals(Color.Transparent, blend(Color.Transparent to 1f,
            style = MaterialAverageStyle(1f, GlassTone(.5f, 2f, false))))
    }

    @Test fun invalidWeightsColorsOverflowAndStylesAreExplicitlyRejected() {
        for (weight in listOf(-.1f, Float.NaN, Float.POSITIVE_INFINITY)) rejects { blend(Color.Red to weight) }
        rejects { blend(Color.Unspecified to 1f) }
        rejects { blend(Color.Unspecified to 0f) }
        rejects { blend(Color(1f, 0f, 0f, colorSpace = ColorSpaces.LinearSrgb) to 1f) }
        rejects { blend(Color.Red to 1f, Color.Blue to .1f) }
        rejects { blend(Color.Transparent to Float.MAX_VALUE, Color.Transparent to Float.MAX_VALUE) }
        val invalid = listOf(MaterialAverageStyle(Float.NaN, GlassTone()), MaterialAverageStyle(-.1f, GlassTone()),
            MaterialAverageStyle(1.1f, GlassTone()), MaterialAverageStyle(0f, GlassTone(Float.NaN)),
            MaterialAverageStyle(0f, GlassTone(-.1f)), MaterialAverageStyle(0f, GlassTone(.6f)),
            MaterialAverageStyle(0f, GlassTone(0f, Float.POSITIVE_INFINITY)),
            MaterialAverageStyle(0f, GlassTone(0f, .9f)), MaterialAverageStyle(0f, GlassTone(0f, 2.1f)))
        invalid.forEach { style -> rejects { blend(Color.Red to 1f, style = style) } }
    }

    @Test fun inputContributorListIsNotMutatedOrRetainedAcrossCalls() {
        val input = mutableListOf(Color.Red to .25f, Color.Blue to .75f)
        val before = input.toList()
        val first = materialAverageColor(input, neutral)
        assertEquals(before, input)
        input.clear()
        color(Color(.25f, 0f, .75f), first)
        assertEquals(Color.Transparent, materialAverageColor(input, neutral))
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid material input must be explicitly rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
