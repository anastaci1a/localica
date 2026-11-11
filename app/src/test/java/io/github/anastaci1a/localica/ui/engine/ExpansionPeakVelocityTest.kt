package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlin.math.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #846: the expansion laws never move faster than the v0.33.21 motion they replaced, from rest, at any size, speed step or platform scale. */
class ExpansionPeakVelocityTest {
    /** Peak of the critically damped v0.33.21 popup spring (k = 90 speed^2), in travel per second: w / e. */
    private fun springPeak(speed: Double) = sqrt(90.0) * speed / E
    /** Peak of the v0.33.21 300 ms (.22, 0, .12, 1) sheet tween, in travel per second. */
    private val tweenPeak = 2.9411764705882355 / .3
    private fun peak(trajectory: Eased, samples: Int = 60000) = (0..samples).maxOf { abs(trajectory.velocity(trajectory.duration * it / samples)) }

    @Test fun popupLawPeakStaysAtOrBelowTheV03321SpringAtEverySizeAndSpeed() {
        val spec = StockUiSpec.expansion
        for (sigma in listOf(1.0, 2.0 / 3)) for (ratio in listOf(.01, .2, .57, 1.0, 2.5)) {
            val duration = expansionDuration(spec, ratio * 1000, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            val law = Eased(0.0, 0.0, 1.0, spec.core, duration, sigma)
            val ceiling = springPeak(4.0 / 3 / sigma)
            assertTrue("ratio $ratio sigma $sigma: ${peak(law)} > $ceiling", peak(law) <= ceiling)
            // Still a front-loaded law: half the travel is covered in under a third of the duration.
            assertTrue((0..1000).first { law.value(duration * it / 1000) >= .5 } < 340)
        }
    }

    @Test fun sheetLawKeepsTheV03321TweenPeakAndSixtySixPercentTime() {
        val spec = StockUiSpec.sheetExpansion
        for (sigma in listOf(1.0, 2.0 / 3)) {
            val duration = expansionDuration(spec, 1000.0, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.3 * sigma, duration, 1e-9)
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
        for (sigma in listOf(1.0, 2.0 / 3)) for (ratio in listOf(.2, 1.0)) {
            val duration = expansionDuration(spec, ratio * 1000, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120)
            assertEquals(.4125 * sigma, duration, 1e-9)
            val law = Eased(0.0, 0.0, 1.0, spec.core, duration, sigma)
            assertEquals(fastOutSlowInPeak / (.4125 * sigma), peak(law), 1e-3 * fastOutSlowInPeak / (.4125 * sigma))
        }
    }
}
