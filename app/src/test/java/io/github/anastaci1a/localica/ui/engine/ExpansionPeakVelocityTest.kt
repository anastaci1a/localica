package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
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
    /** #608 geometry (analysis/data/geometry.json, dp at 720 x 1280 px, density 1.75): the measured Sort/View anchor and target, and the full player. */
    private val sortView = basis(listOf(302.2857f, 26.2857f, 399.4286f, 77.7143f), listOf(40.5714f, 96.5714f, 370.8571f, 611.4286f))
    private val fullPlayer = basis(listOf(12f, 576.2286f, 399.4286f, 627.4286f), listOf(0f, 0f, 411.4286f, 731.4286f))

    @Test fun durationTravelCountsOriginDriftPlusSizeChange() {
        // Pure translation: the half-extent is unchanged, so D is the center's travel.
        assertEquals(576f, basis(listOf(0f, 600f, 400f, 1300f), listOf(0f, 24f, 400f, 724f)).objectTravelPx, 1e-3f)
        // Pure growth about a fixed center: D is the half-extent's change.
        assertEquals(hypot(50f, 25f), basis(listOf(100f, 400f, 200f, 450f), listOf(50f, 375f, 250f, 475f)).objectTravelPx, 1e-3f)
        // Both (operator 2026-11-11): |delta center| + |delta half-extent|, which exceeds the greatest edge (533.7 dp).
        assertEquals(335.06785f + 259.38467f, sortView.objectTravelPx, 1e-2f)
        assertEquals(533.7143f, sortView.edgeTravelPx, 1e-3f)
        assertEquals(236.1143f + 340.32593f, fullPlayer.objectTravelPx, 1e-2f)
    }

    @Test fun sortViewPopupMatchesTheV03321SpringAndOtherPopupsScaleByTravel() {
        val spec = StockUiSpec.expansion
        val reference = fullPlayer.objectTravelPx.toDouble()
        val sortViewTravel = sortView.objectTravelPx.toDouble()
        for (sigma in listOf(1.0, 2.0 / 3)) {
            // The operator's base case (#846): v0.33.21's Sort/View grow reaches 90 % at 308 ms (x2/3 Faster).
            val base = expansionDuration(spec, sortViewTravel, reference, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.5230313 * sigma, base, 3e-6)
            val law = Eased(0.0, 0.0, 1.0, spec.core, base, sigma)
            assertEquals(.308 * sigma, timeTo(law, .9), 3e-5)
            assertEquals(springPeak(4.0 / 3 / sigma), peak(law), .02 * springPeak(4.0 / 3 / sigma))
            // Every other popup scales from it by (travel / Sort/View travel)^.5 (U02:156), above the 140 ms floor.
            for (travel in listOf(114.75, 405.1, 576.4, 619.4, 1200.0)) {
                val duration = expansionDuration(spec, travel, reference, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
                assertEquals(base * sqrt(travel / sortViewTravel), duration, 1e-6)
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
