package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class GlassShadowStrengthCurveTest {
    private val unit = 0f..1f

    @Test fun positionFollowsThePowerCurveAndRoundTripsToStrength() {
        for (strength in listOf(0f, .1f, .5f, 1f)) {
            val position = sliderPosition(strength, unit, GlassShadowStrengthCurve)
            assertEquals(strength.toDouble().pow(1.0 / 1.8), position.toDouble(), 1e-6)
            assertEquals(strength, sliderValue(position, unit, GlassShadowStrengthCurve), 1e-6f)
        }
    }

    @Test fun lowStrengthsGetMoreSliderTravel() {
        assertTrue(sliderPosition(.25f, unit, GlassShadowStrengthCurve) > .25f)
    }
}
