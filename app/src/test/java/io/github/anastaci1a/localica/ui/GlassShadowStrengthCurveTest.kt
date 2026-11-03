package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class GlassShadowStrengthCurveTest {
    private val unit = 0f..1f

    @Test fun positionFollowsThePowerCurveAndRoundTripsToStrength() {
        for (strength in listOf(0f, .1f, .5f, 1f)) {
            val position = sliderPosition(strength, unit, GlassShadowStrengthCurve)
            assertEquals(strength.toDouble().pow(1.0 / GlassShadowStrengthCurve.toDouble()), position.toDouble(), 1e-6)
            assertEquals(strength, sliderValue(position, unit, GlassShadowStrengthCurve), 1e-6f)
        }
    }

    @Test fun lowStrengthsGetMoreSliderTravel() {
        assertTrue(sliderPosition(.25f, unit, GlassShadowStrengthCurve) > .25f)
    }

    @Test fun endpointsAreExact() {
        assertEquals(0f, sliderValue(0f, unit, GlassShadowStrengthCurve), 0f)
        assertEquals(1f, sliderValue(1f, unit, GlassShadowStrengthCurve), 0f)
        assertEquals(0f, sliderPosition(0f, unit, GlassShadowStrengthCurve), 0f)
        assertEquals(1f, sliderPosition(1f, unit, GlassShadowStrengthCurve), 0f)
    }

    @Test fun mappingIsMonotone() {
        var previous = -1f
        for (k in 0..1000) {
            val value = sliderValue(k / 1000f, unit, GlassShadowStrengthCurve)
            assertTrue("Strength must not decrease at step $k", value >= previous)
            previous = value
        }
    }

    @Test fun lowerHalfIsMoreGranularThanTheSection502Curve() {
        // Every 5% track step from 0 to 50% must cover less strength than under #502's curve.
        for (k in 1..10) {
            val position = k / 20f
            val step = sliderValue(position, unit, GlassShadowStrengthCurve) - sliderValue(position - .05f, unit, GlassShadowStrengthCurve)
            val baseline = sliderValue(position, unit, GlassShadowStrengthCurve502) - sliderValue(position - .05f, unit, GlassShadowStrengthCurve502)
            assertTrue("Step at ${k * 5}% track must be finer than #502: $step vs $baseline", step < baseline)
        }
    }

    @Test fun readoutIsTheTrackPositionRoundedToFivePercent() {
        for (strength in listOf(0f, .01f, .05f, .12f, .2f, .3f, .5f, .75f, .9f, 1f)) {
            val label = shadowStrengthReadout(strength)
            val position = strength.toDouble().pow(1.0 / GlassShadowStrengthCurve.toDouble())
            assertEquals("Label must step in 5%: strength $strength", 0, label % 5)
            assertEquals("Label for strength $strength", (Math.round(position * 20.0) * 5).toInt(), label)
        }
    }

    @Test fun readoutEndpointsAreExact() {
        assertEquals(0, shadowStrengthReadout(0f))
        assertEquals(100, shadowStrengthReadout(1f))
    }

    @Test fun fivePercentTrackStopsRoundTripThroughTheCurve() {
        // The slider's 19 interior stops sit on the 5% track grid (steps = 19); each stop maps back to its index.
        val stops = (0..20).map { (sliderPosition(sliderValue(it / 20f, unit, GlassShadowStrengthCurve), unit, GlassShadowStrengthCurve) * 20f).roundToInt() }
        assertEquals((0..20).toList(), stops)
    }
}
