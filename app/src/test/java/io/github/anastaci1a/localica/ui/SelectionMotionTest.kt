package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SelectionMotionTest {
    @Test fun revealScalesAroundCenterAndPreservesFinalContour() {
        val bounds = Rect(20f, 30f, 320f, 94f)
        for (step in 0..20) {
            val p = step / 20f
            val scaled = selectionRevealBounds(bounds, p)
            assertEquals(bounds.center, scaled.center)
            assertEquals(bounds.width * (.92f + .08f * p), scaled.width, .0001f)
            assertEquals(bounds.height * (.92f + .08f * p), scaled.height, .0001f)
        }
        assertEquals(bounds, selectionRevealBounds(bounds, 1f))
        assertEquals(bounds, selectionRevealBounds(bounds, 2f))
    }

    @Test fun arrivalsAndDeparturesBothEaseOutWithoutOvershoot() {
        for ((from, to) in listOf(0f to 1f, 1f to 0f, .3f to 1f, .7f to 0f)) {
            val animation = TargetBasedAnimation(selectionMotionSpec(false), Float.VectorConverter, from, to)
            val samples = (0..180 step 15).map { animation.getValueFromNanos(it * 1_000_000L) }
            assertEquals(from, samples.first(), 0f)
            assertEquals(to, samples.last(), 0f)
            assertTrue(samples.all { it in minOf(from, to)..maxOf(from, to) })
            assertTrue(samples.zipWithNext().all { (a, b) -> if (from < to) b >= a else b <= a })
            assertTrue("Ease-out travels more than half the distance by halfway",
                kotlin.math.abs(samples[6] - from) > kotlin.math.abs(to - from) / 2f)
        }
    }

    @Test fun reducedMotionHasImmediateSharedEndpoints() {
        val animation = TargetBasedAnimation(selectionMotionSpec(true), Float.VectorConverter, 0f, 1f)
        assertEquals(0L, animation.durationNanos)
        assertEquals(1f, animation.getValueFromNanos(0), 0f)
    }
}
