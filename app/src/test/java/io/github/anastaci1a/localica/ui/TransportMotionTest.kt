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
        assertTrue(SkipTaperMargin > 0f && SkipTaperBand > 0f)
        assertEquals(SkipRestInset, SkipTaperMargin + SkipTaperBand, .00001f)
        // Rest wedges span the rest bounds; the mask is opaque there and a margin beyond.
        for (i in 0..1000) {
            val x = SkipRestInset - SkipTaperMargin + i / 1000f * (1f - 2f * SkipTaperBand)
            assertEquals("Opaque at $x", 1f, skipTaperAlpha(x), .0001f)
        }
        assertEquals(0f, skipTaperAlpha(0f), 0f)
        assertEquals(0f, skipTaperAlpha(1f), 0f)
        var previous = 0f
        var levels = 0
        for (i in 1..1000) {
            val x = i / 1000f * SkipTaperBand
            val alpha = skipTaperAlpha(x)
            assertTrue("Ramp at $x", alpha >= previous && alpha - previous < .01f)
            if (alpha > previous) levels++
            previous = alpha
            assertEquals("Symmetric at $x", alpha, skipTaperAlpha(1f - x), .0001f)
        }
        assertTrue("Ramps rather than steps", levels > 500)
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
