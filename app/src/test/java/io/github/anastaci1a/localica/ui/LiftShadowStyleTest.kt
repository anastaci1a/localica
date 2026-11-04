package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** #553: lift shadow = Glass spread exactly, a 50-150% multiple of Glass strength, shadow or glow by ink. */
class LiftShadowStyleTest {
    @Test fun spreadIsGlassSpreadExactlyAndStrengthIsAMultipleOfGlassStrength() {
        for (spread in listOf(0f, .1f, .6f, 1f)) for (strength in listOf(0f, .12f, .5f, 1f)) for (multiple in listOf(.5f, 1f, 1.5f)) {
            val glass = GlassAppearance(shadowStrength = strength, shadowSpread = spread, liftShadowStrength = multiple)
            for (light in listOf(false, true)) {
                val lift = liftShadow(glass, light)
                assertEquals(spread, lift.spread, 0f)
                assertEquals(strength * multiple, lift.strength, 1e-6f)
                assertEquals(if (light) Color.White else Color.Black, lift.color)
            }
        }
    }

    @Test fun defaultIsExactlyTheGlassShadowAndGlowShareTheStrength() {
        val glass = GlassAppearance()
        assertEquals(1f, glass.liftShadowStrength, 0f)
        assertEquals(glass.shadowStrength, liftShadow(glass, false).strength, 0f)
        assertEquals(liftShadow(glass, false).strength, liftShadow(glass, true).strength, 0f)
    }

    @Test fun strengthRangeIsFiftyToOneFiftyInPlainFivePercentSteps() {
        assertEquals(.5f, LiftShadowMin, 0f)
        assertEquals(1.5f, LiftShadowMax, 0f)
        assertEquals(19, LiftShadowSteps)
        val grid = (0..20).map { .5f + it * .05f }
        for (value in grid) assertEquals(value, normalizedLiftShadowStrength(value), 1e-5f)
        assertEquals(1.25f, normalizedLiftShadowStrength(1.23f), 1e-5f)
        assertEquals(.5f, normalizedLiftShadowStrength(0f), 0f)
        assertEquals(1.5f, normalizedLiftShadowStrength(9f), 0f)
        assertEquals(1f, normalizedLiftShadowStrength(Float.NaN), 0f)
        assertEquals(1f, GlassAppearance(liftShadowStrength = Float.POSITIVE_INFINITY).normalized().liftShadowStrength, 0f)
        assertEquals(1.1f, GlassAppearance(liftShadowStrength = 1.1f).normalized().liftShadowStrength, 1e-5f)
        // Steps between min and max, with no extra weighting: equal increments.
        val steps = grid.zipWithNext { a, b -> b - a }
        assertTrue(steps.all { kotlin.math.abs(it - .05f) < 1e-5f })
    }
}
