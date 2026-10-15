package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PressIlluminationTest {
    private val shape = LensGeometry(Rect(0f, 0f, 200f, 100f), 30f)
    @Test fun resolvesFiniteBoundsAndRoundedCornerOrigins() {
        for (origin in listOf(Offset(-1000f, -1000f), Offset(200f, 0f), Offset(100f, 50f), Offset(Float.MAX_VALUE, 0f))) {
            val p = PressIllumination(2f, origin, -10f).resolve(shape)
            assertEquals(1f, p.amount, 0f); assertEquals(1f, p.spread, 0f)
            assertTrue(shape.field(p.origin).distance >= -.0001f)
            assertTrue(p.weight(p.origin).isFinite())
        }
        assertEquals(400f, PressIllumination(1f, Offset.Zero, Float.MAX_VALUE).resolve(shape).spread, 0f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { PressIllumination(bad, Offset.Zero, 1f) }
    }
    @Test fun zeroIsIdentityAndLiftIsBoundedMonotonicAndSymmetric() {
        for (amount in listOf(0f, .5f, 1f)) {
            val p = PressIllumination(amount, shape.bounds.center, 20f).resolve(shape)
            for (x in 0..200) {
                assertEquals(p.weight(Offset(x.toFloat(), 50f)), p.weight(Offset(200f - x, 50f)), .000001f)
                assertTrue(p.weight(Offset(x.toFloat(), 50f)) in 0f..PressIllumination.Peak)
                for (v in listOf(0f, .2f, .5f, .75f)) {
                    val result = p.channel(v, .75f, Offset(x.toFloat(), 50f))
                    assertTrue(result in v.. .75f)
                    if (amount == 0f) assertEquals(v, result, 0f)
                }
            }
        }
    }
    @Test fun translatedOutlinePreservesSurfaceLocalWeightsAndHugeSpreadStaysFinite() {
        val shift = Offset(23f, -17f)
        val a = PressIllumination(.7f, Offset(-5f, 10f), 16f).resolve(shape)
        val b = PressIllumination(.7f, Offset(-5f, 10f) + shift, 16f)
            .resolve(LensGeometry(shape.bounds.translate(shift), shape.corner))
        for (x in 0..200) {
            val p = Offset(x.toFloat(), 40f)
            assertEquals(a.weight(p), b.weight(p + shift), .000001f)
        }
        val huge = PressIllumination(1f, Offset.Zero, Float.MAX_VALUE)
            .resolve(LensGeometry(Rect(0f, 0f, Float.MAX_VALUE, 100f), 0f))
        assertTrue(huge.spread.isFinite())
        assertTrue((huge.spread * PressIllumination.GradientExtent).isFinite())
        assertTrue(huge.weight(Offset(Float.MAX_VALUE, 50f)).isFinite())
    }

    @Test fun radialGradientApproximationHasPredeclaredScalarError() {
        val p = PressIllumination(1f, Offset.Zero, 1f)
        var maximum = 0f
        for (i in 0..40000) {
            val distance = i / 10000f
            val index = distance / PressIllumination.GradientExtent * PressIllumination.GradientSteps
            val lo = index.toInt().coerceAtMost(PressIllumination.GradientSteps - 1)
            fun sample(step: Int) = if (step == PressIllumination.GradientSteps) 0f else
                p.weight(Offset(step * PressIllumination.GradientExtent / PressIllumination.GradientSteps, 0f))
            val approximation = sample(lo) + (sample(lo + 1) - sample(lo)) * (index - lo)
            maximum = maxOf(maximum, abs(approximation - p.weight(Offset(distance, 0f))))
        }
        assertTrue("Gradient interpolation error $maximum", maximum < .0001f)
    }
}
