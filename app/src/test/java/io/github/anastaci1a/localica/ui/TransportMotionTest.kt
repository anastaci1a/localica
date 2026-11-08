package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class TransportMotionTest {
    @Test fun scrollingCyclesHaveIdenticalEndpointsAndSymmetricCrossfades() {
        assertEquals(skipElements(0f), skipElements(1f))
        assertEquals(skipElements(0f), skipElements(200f))
        for (i in 1..999) {
            val p = i / 1000f
            val a = skipElements(p)
            val b = skipElements(1f - p)
            assertEquals(1f, a[0].alpha + a[2].alpha, .00001f)
            assertEquals(a[0].alpha, b[2].alpha, .00001f)
            assertEquals(.4f, a[1].x - a[0].x, .00001f)
            assertEquals(.4f, a[2].x - a[1].x, .00001f)
            assertEquals(1f, a[1].alpha, 0f)
        }
    }

    @Test fun edgeTaperRampsSymmetricallyOutsideTheRestBounds() {
        assertEquals(.25f, SkipTaperMargin, 0f)
        assertEquals(.25f, SkipTaperBand, 0f)
        for ((left, right) in listOf(.08f to .895f, .2f to .8f, .1f to .45f)) {
            val width = right - left
            for (i in 0..1000) {
                val distance = i / 1000f * width
                val l = skipTaperAlpha(left - distance, left, right)
                val r = skipTaperAlpha(right + distance, left, right)
                assertEquals("Symmetric for ink $left..$right", l, r, .00001f)
                if (distance <= width * SkipTaperMargin) assertEquals(1f, l, .00001f)
                if (distance >= width * (SkipTaperMargin + SkipTaperBand)) assertEquals(0f, l, .00001f)
                if (i > 0) {
                    val before = skipTaperAlpha(left - (i - 1) / 1000f * width, left, right)
                    assertTrue("No spatial alpha step", kotlin.math.abs(before - l) < .005f)
                }
            }
            // Equal normalized band positions and alpha ramps for stock and padded pack glyphs.
            for (i in 0..100) {
                val distance = (.25f + .25f * i / 100f) * width
                assertEquals(1f - i / 100f, skipTaperAlpha(left - distance, left, right), .00001f)
            }
        }
    }

    @Test fun easingIsMonotonicWithSmoothEndsAndALongerSettle() {
        var previous = 0f
        for (i in 0..1000) {
            val value = SkipEasing.transform(i / 1000f)
            assertTrue("Monotonic at $i", value >= previous)
            previous = value
        }
        assertEquals(0f, SkipEasing.transform(0f), 0f)
        assertEquals(1f, SkipEasing.transform(1f), 0f)
        assertTrue(SkipEasing.transform(.01f) < .002f)
        assertTrue(1f - SkipEasing.transform(.99f) < .002f)
        fun firstTimeAt(value: Float) = (0..1000).first { SkipEasing.transform(it / 1000f) >= value } / 1000f
        assertTrue("Last 10% takes longer than first 10%", 1f - firstTimeAt(.9f) > firstTimeAt(.1f))
        assertEquals(380, skipDuration(4f / 3f))
        assertEquals(253, skipDuration(2f))
    }

    @Test fun repeatedPressRetargetPreservesPositionAndIncomingVelocity() {
        for (t in listOf(.05f, .25f, .5f, .8f, .98f)) {
            val current = SkipEasing.transform(t)
            val h = .001f
            val velocity = (SkipEasing.transform(t + h) - SkipEasing.transform(t - h)) / (2f * h * .38f)
            val target = 2f
            val easing = skipRetargetEasing(velocity, target - current, 380)
            val atHandoff = current + (target - current) * easing.transform(0f)
            assertEquals(current, atHandoff, 0f)
            val newVelocity = (target - current) * easing.transform(.001f) / .00038f
            assertEquals("Retarget retains velocity at $t", velocity, newVelocity, .1f)
            assertEquals(skipElements(current), skipElements(atHandoff))
            assertEquals(skipElements(0f), skipElements(target))
        }
    }
}
