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

    @Test fun edgeTaperStartsAtInkAndReachesZeroAtTheOutermostTriangleEdge() {
        for ((left, right, pitch) in listOf(Triple(.08f, .895f, .4f), Triple(.2f, .8f, .3f))) {
            assertEquals(1f, skipTaperAlpha(left, left, right, pitch), 0f)
            assertEquals(1f, skipTaperAlpha(right, left, right, pitch), 0f)
            assertEquals(0f, skipTaperAlpha(left - pitch, left, right, pitch), .00001f)
            assertEquals(0f, skipTaperAlpha(right + pitch, left, right, pitch), .00001f)
            for (i in 0..1000) {
                val distance = i / 1000f * pitch
                val l = skipTaperAlpha(left - distance, left, right, pitch)
                val r = skipTaperAlpha(right + distance, left, right, pitch)
                assertEquals("Symmetric bands", l, r, 1f / 255f)
                assertEquals("Linear ramp", 1f - i / 1000f, l, .00001f)
            }
        }
    }

    @Test fun packTravelIsOneTriangleAndBothSourceHalvesConvergeToTheirStaticEndpoints() {
        for (width in listOf(.6f, .815f, 1f)) {
            val a = packSkipElements(.25f, width)
            val b = packSkipElements(.75f, width)
            for (i in a.indices) assertEquals("Half cycle travels half of one triangle", width / 4f, b[i].offset - a[i].offset, .00001f)
            assertEquals(packSkipElements(0f, width), packSkipElements(1f, width))
            for (phase in listOf(.00001f, .99999f)) {
                val visible = packSkipElements(phase, width).filter { it.alpha > .5f }
                assertEquals(2, visible.size)
                assertEquals(setOf(false, true), visible.map { it.rightHalf }.toSet())
                visible.forEach {
                    assertTrue("Visible halves approach their exact static positions", kotlin.math.abs(it.offset) <= width * .00001f)
                    assertTrue("Visible halves approach full opacity", it.alpha >= .99998f)
                }
                assertTrue(packSkipElements(phase, width).filter { it.alpha < .5f }.all { it.alpha <= .00002f })
                val stock = skipElements(phase).filter { it.alpha > .5f }
                for ((i, element) in stock.withIndex()) {
                    assertEquals("Stock positions converge", listOf(.08f, .48f)[i], element.x, .00001f)
                    assertTrue(element.alpha >= .99998f)
                }
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
