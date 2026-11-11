package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #846: each expansion law keeps the v0.33.21 motion it replaced, from rest, at both speed steps. */
class ExpansionPeakVelocityTest {
    /** Peak of the critically damped v0.33.21 popup spring (k = 90 speed^2), in travel per second: w / e. */
    private fun springPeak(speed: Double) = sqrt(90.0) * speed / E
    /** Peak of the v0.33.21 300 ms (.22, 0, .12, 1) sheet tween, in travel per second. */
    private val tweenPeak = 2.9411764705882355 / .3
    private fun peak(trajectory: Eased, samples: Int = 60000) = (0..samples).maxOf { abs(trajectory.velocity(trajectory.duration * it / samples)) }
    private fun timeTo(law: Eased, fraction: Double, samples: Int = 60000) = (0..samples).first { law.value(law.duration * it / samples) >= fraction } * law.duration / samples
    private fun basis(start: List<Float>, end: List<Float>) =
        ExpansionBasis(Rect(start[0], start[1], start[2], start[3]), Rect(end[0], end[1], end[2], end[3]), 1f)
    /** #608 geometry (analysis/data/geometry.json, dp at 720 x 1280 px, density 1.75). The measure is scale invariant, so dp stand in for px. */
    private val sortView = basis(listOf(302.2857f, 26.2857f, 399.4286f, 77.7143f), listOf(40.5714f, 96.5714f, 370.8571f, 611.4286f))
    private val fullPlayer = basis(listOf(12f, 576.2286f, 399.4286f, 627.4286f), listOf(0f, 0f, 411.4286f, 731.4286f))
    private val selectionPill = basis(listOf(8f, 438.8f, 372f, 490f), listOf(122.75f, 438.8f, 257.25f, 490f))
    private val rowMenu = basis(listOf(351.4286f, 300f, 399.4286f, 348f), listOf(165.1429f, 46.8571f, 399.4286f, 675.4286f))
    private val queue = basis(listOf(0f, 643.4286f, 411.4286f, 1350.8571f), listOf(0f, 24f, 411.4286f, 731.4286f))

    @Test fun durationTravelIsTheVanWijkNuijPathLength() {
        val rho = sqrt(2.0)
        // Pure zoom about a fixed center: S = |ln(w1 / w0)| / rho, w = sqrt(width x height).
        assertEquals(ln(2.0) / rho, basis(listOf(100f, 100f, 200f, 200f), listOf(50f, 50f, 250f, 250f)).objectTravel.toDouble(), 1e-6)
        // Pure pan at constant size w: S = 2 asinh(rho^2 u / (2 w)) / rho.
        assertEquals(2 * asinh(rho * rho * 300 / 200) / rho, basis(listOf(0f, 0f, 100f, 100f), listOf(300f, 0f, 400f, 100f)).objectTravel.toDouble(), 1e-5)
        // u -> 0 approaches the pure-zoom limit continuously.
        assertEquals(ln(2.0) / rho, expansionTravel(doubleArrayOf(100.0, 100.0, 200.0, 200.0), doubleArrayOf(50.0001, 50.0, 250.0001, 250.0)), 1e-6)
        // Mixed drift and zoom, from the measured #608 rectangles; contact drags keep the greatest edge.
        assertEquals(2.1650267, sortView.objectTravel.toDouble(), 1e-4)
        assertEquals(1.3650654, fullPlayer.objectTravel.toDouble(), 1e-4)
        assertEquals(533.7143f, sortView.edgeTravelPx, 1e-3f)
        // Symmetric, and bounded by the admission domain at the evaluation viewport.
        assertEquals(sortView.objectTravel, basis(listOf(40.5714f, 96.5714f, 370.8571f, 611.4286f), listOf(302.2857f, 26.2857f, 399.4286f, 77.7143f)).objectTravel, 1e-5f)
        assertTrue(listOf(sortView, fullPlayer, selectionPill, rowMenu, queue).all { it.objectTravel < expansionTravelBound(hypot(411.4286, 731.4286)) })
    }

    @Test fun sortViewPopupMatchesTheV03321SpringAndEveryPopupFollowsTheChosenTable() {
        val spec = StockUiSpec.expansion
        val reference = fullPlayer.objectTravel.toDouble()
        for (sigma in listOf(1.0, 2.0 / 3)) {
            // The operator's base case (#846): v0.33.21's Sort/View grow reaches 90 % at 308 ms (x2/3 Faster).
            val base = expansionDuration(spec, sortView.objectTravel.toDouble(), reference, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.5230313 * sigma, base, 2e-5)
            val law = Eased(0.0, 0.0, 1.0, spec.core, base, sigma)
            assertEquals(.308 * sigma, timeTo(law, .9), 1e-4)
            assertEquals(springPeak(4.0 / 3 / sigma), peak(law), .02 * springPeak(4.0 / 3 / sigma))
            // The operator-approved table (k = .5, scale 1, Faster off; x2/3 Faster): 90 % times in seconds.
            for ((popup, t90) in listOf(selectionPill to .12419, rowMenu to .26827, sortView to .308, fullPlayer to .24457, queue to .24672)) {
                val duration = expansionDuration(spec, popup.objectTravel.toDouble(), reference, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
                assertEquals(base * sqrt(popup.objectTravel / sortView.objectTravel.toDouble()), duration, 1e-6)
                assertEquals(t90 * sigma, timeTo(Eased(0.0, 0.0, 1.0, spec.core, duration, sigma), .9), 5e-4)
            }
            assertEquals(.14 * sigma, expansionDuration(spec, 0.0, reference, 0.0, 0.0, 1.0, sigma, 1.0 / 120), 1e-9)
        }
    }

    @Test fun sheetLawKeepsTheV03321TweenPeakAndSixtySixPercentTime() {
        val spec = StockUiSpec.sheetExpansion
        for (sigma in listOf(1.0, 2.0 / 3)) {
            val duration = expansionDuration(spec, 1000.0, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.3 * sigma, duration, 1e-9)
            // Size exponent 0: the Queue drawer, which travels farther than the player, keeps the same 300 ms.
            for (ratio in listOf(.2, 1.075, 2.0)) assertEquals(.3 * sigma, expansionDuration(spec, ratio * 1000, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120), 1e-9)
            val law = Eased(0.0, 0.0, 1.0, spec.core, duration, sigma)
            assertTrue(peak(law) <= tweenPeak / sigma * 1.0000001)
            val t66 = (0..30000).first { law.value(duration * it / 30000) >= .66 } * duration / 30000
            assertEquals(.1017 * sigma, t66, .0015)
        }
    }

    @Test fun lyricsLawReproducesTheV03321LayoutTweenDurationAndPeak() {
        // v0.33.21 PlayerSurface: tween(motionDuration(550)) with FastOutSlowIn = 412.5 ms (275 ms Faster).
        val spec = StockUiSpec.lyricsExpansion
        val fastOutSlowInPeak = 2.7345908033899957
        for (sigma in listOf(1.0, 2.0 / 3)) for (ratio in listOf(.2, 1.0, 1.5)) {
            val duration = expansionDuration(spec, ratio * 1000, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.4125 * sigma, duration, 1e-9)
            val law = Eased(0.0, 0.0, 1.0, spec.core, duration, sigma)
            assertEquals(fastOutSlowInPeak / (.4125 * sigma), peak(law), 1e-3 * fastOutSlowInPeak / (.4125 * sigma))
        }
    }
}
