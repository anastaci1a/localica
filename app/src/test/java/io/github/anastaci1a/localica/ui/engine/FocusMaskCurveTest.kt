package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.StockFocusSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** #640 §4.4 FocusMaskCurveTest: β is C1 in hold progress and in space at the front, zero on the item, full far away. */
class FocusMaskCurveTest {
    private val density = 2.625f
    private val viewport = Rect(0f, 0f, 411f * density, 891f * density)
    private val item = Rect(16f * density, 400f * density, 395f * density, 456f * density)
    private val radius = 12f * density
    private val feather = StockFocusSpec.maskFeatherDp * density
    private fun beta(x: Float, y: Float, h: Float) = FocusMask.at(x, y, item, radius, viewport, h, feather, StockFocusSpec.blurFront, StockFocusSpec.blurStrength)

    @Test fun roundedRectDistanceIsSignedAndExactOnAxes() {
        val r = Rect(0f, 0f, 100f, 50f)
        assertEquals(-25f, FocusMask.roundedRectDistance(50f, 25f, r, 0f), 1e-5f)
        assertEquals(10f, FocusMask.roundedRectDistance(110f, 25f, r, 0f), 1e-5f)
        assertEquals(10f, FocusMask.roundedRectDistance(50f, -10f, r, 0f), 1e-5f)
        assertEquals(0f, FocusMask.roundedRectDistance(100f, 25f, r, 0f), 1e-5f)
        // Corner: with radius 10 the corner point sits sqrt(2)·10 − 10 outside the rounded edge.
        assertEquals(sqrt(2f) * 10f - 10f, FocusMask.roundedRectDistance(100f, 50f, r, 10f), 1e-4f)
        assertEquals(0f, FocusMask.roundedRectDistance(100f, 25f, r, 10f), 1e-5f)
        assertThrows(IllegalArgumentException::class.java) { FocusMask.roundedRectDistance(0f, 0f, r, 30f) }
    }

    @Test fun frontStartsAtTheFarthestCornerAndReachesTheItemAtFullSweep() {
        val dMax = FocusMask.frontStart(item, viewport, radius)
        assertEquals(FocusMask.roundedRectDistance(viewport.right, viewport.bottom, item, radius), dMax, 1e-3f)
        assertEquals(dMax, FocusMask.front(0f, dMax), 0f)
        assertEquals(0f, FocusMask.front(1f, dMax), 0f)
    }

    @Test fun theItemIsNeverBlurredAndTheFarCornerIsFullyBlurredWhenLifted() {
        for (h in listOf(0f, .2f, .5f, .8f, 1f)) {
            assertEquals("centre h=$h", 0f, beta(item.center.x, item.center.y, h), 0f)
            assertEquals("edge h=$h", 0f, beta(item.right, item.center.y, h), 0f)
        }
        assertEquals(1f, beta(viewport.right - 1f, viewport.bottom - 1f, 1f), 1e-4f)
        assertEquals(0f, beta(viewport.right - 1f, viewport.bottom - 1f, 0f), 0f)
        // Inside the feather the blur is partial.
        val partial = beta(item.right + feather / 2f, item.center.y, 1f)
        assertTrue("$partial", partial > .4f && partial < .6f)
    }

    @Test fun betaIsC1InSpaceAcrossTheFront() {
        val h = .6f
        val x = item.center.x
        var y = item.bottom
        var previous = beta(x, y, h); var previousSlope = 0f
        val step = .5f
        var maxJump = 0f
        while (y < viewport.bottom - step) {
            y += step
            val value = beta(x, y, h)
            val slope = (value - previous) / step
            maxJump = maxOf(maxJump, abs(slope - previousSlope))
            previous = value; previousSlope = slope
        }
        // A C1 function's finite-difference slope changes by O(step · max |β″|); β″ ≤ 6/F² for the smoothstep.
        assertTrue("slope jump $maxJump", maxJump <= 6f / (feather * feather) * step * 1.5f + 1e-6f)
    }

    private fun maxSlopeJumpInH(x: Float, y: Float, step: Float): Float {
        var previous = beta(x, y, 0f); var previousSlope = 0f
        var maxJump = 0f
        var h = 0f
        while (h < 1f - step) {
            h += step
            val value = beta(x, y, h)
            val slope = (value - previous) / step
            maxJump = maxOf(maxJump, abs(slope - previousSlope))
            previous = value; previousSlope = slope
        }
        return maxJump
    }

    @Test fun betaIsC1InHoldProgress() {
        val x = viewport.right - 10f; val y = viewport.top + 10f
        // The front sweeps Dmax ≈ 2000 px across a 168 px feather, so β″ in h is large but finite: for a C1 function
        // the finite-difference slope jump shrinks linearly with the step, while a kink would keep a constant jump.
        val coarse = maxSlopeJumpInH(x, y, 4e-3f); val medium = maxSlopeJumpInH(x, y, 2e-3f); val fine = maxSlopeJumpInH(x, y, 1e-3f)
        assertTrue("jumps $coarse → $medium → $fine shrink with the step", medium < .62f * coarse && fine < .62f * medium)
        assertEquals(1f, beta(x, y, 1f), 1e-4f)
        assertEquals(0f, beta(x, y, 0f), 0f)
        // Monotone in h far from the item.
        var last = -1f
        for (i in 0..100) { val v = beta(x, y, i / 100f); assertTrue(v >= last); last = v }
    }

    @Test fun smoothstepIsHermiteWithZeroSlopeAtTheJoins() {
        assertEquals(0f, FocusMask.smoothstep(0f, 1f, -1f), 0f); assertEquals(1f, FocusMask.smoothstep(0f, 1f, 2f), 0f)
        assertEquals(.5f, FocusMask.smoothstep(0f, 1f, .5f), 1e-6f)
        assertEquals(.5f, FocusMask.smoothstep(2f, 4f, 3f), 1e-6f)
        val e = 1e-3f
        assertTrue(FocusMask.smoothstep(0f, 1f, e) / e < 4e-3f)
        assertTrue((1f - FocusMask.smoothstep(0f, 1f, 1f - e)) / e < 4e-3f)
        assertThrows(IllegalArgumentException::class.java) { FocusMask.smoothstep(1f, 1f, 0f) }
    }
}

/** #640 §2.2 fallbacks: the composite mode follows the API level and the glass renderer. */
class FocusBlurModeTest {
    @Test fun modeFollowsApiAndGlassRenderer() {
        assertEquals(FocusBlurMode.Shader, focusBlurMode(33, glassNone = false))
        assertEquals(FocusBlurMode.Shader, focusBlurMode(36, glassNone = false))
        assertEquals(FocusBlurMode.Masked, focusBlurMode(31, glassNone = false))
        assertEquals(FocusBlurMode.Masked, focusBlurMode(32, glassNone = false))
        assertEquals(FocusBlurMode.DimOnly, focusBlurMode(30, glassNone = false))
        assertEquals(FocusBlurMode.DimOnly, focusBlurMode(26, glassNone = false))
        for (api in listOf(26, 31, 36)) assertEquals(FocusBlurMode.DimOnly, focusBlurMode(api, glassNone = true))
    }
}
