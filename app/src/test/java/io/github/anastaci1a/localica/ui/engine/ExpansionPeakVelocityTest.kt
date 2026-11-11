package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #846: each expansion law reproduces the v0.33.21 motion it replaced, from rest, at any size, speed step or platform scale. */
class ExpansionPeakVelocityTest {
    /** Peak of the v0.33.21 300 ms (.22, 0, .12, 1) sheet tween, in travel per second. */
    private val tweenPeak = 2.9411764705882355 / .3
    private fun peak(trajectory: Eased, samples: Int = 60000) = (0..samples).maxOf { abs(trajectory.velocity(trajectory.duration * it / samples)) }

    /** v0.33.21 popups: glassMorphSpring(speed) = spring(1f, 90 speed^2, visibilityThreshold = .0001f), speed 4/3 (2 with Faster). */
    private fun v03321Popup(speed: Float) = TargetBasedAnimation(spring(1f, 90f * speed * speed, visibilityThreshold = .0001f), Float.VectorConverter, 0f, 1f)
    /** First real time (ms, 0.1 ms grid) at which [value] reaches [fraction] of the travel. */
    private fun crossing(fraction: Double, value: (Double) -> Double) = (0..30000).first { value(it / 10.0) >= fraction } / 10.0

    @Test fun popupLawIsTheV03321SpringNormalisedOverItsSettleSpan() {
        // (1 + a) e^-a = 1e-4: the spring's .0001 visibility threshold, reached at a / w = 929.42 ms (w = sqrt(90) 4/3).
        assertEquals(1e-4, (1 + PopupSpringSpan.toDouble()) * exp(-PopupSpringSpan.toDouble()), 1e-10)
        assertEquals(PopupSpringSpan / (sqrt(90.0) * 4 / 3) * 1000, PopupSpringMs.toDouble(), 1e-3)
        val spec = StockUiSpec.expansion
        assertEquals(SpringCurveSpec(PopupSpringSpan), spec.core)
        // The unit curve is monotone from 0 to exactly 1, and its slope never exceeds the admitted bound (peak a / (e s) at u = 1 / a).
        val curve = unitCurve(spec.core)
        assertEquals(0.0, curve.at(0.0), 0.0); assertEquals(1.0, curve.at(1.0), 0.0)
        for (i in 1..100000) { val u = i / 100001.0; assertTrue(curve.at(u) >= curve.at((i - 1) / 100001.0)); assertTrue(curve.at(u, true) <= spec.core.maxSlope) }
        assertEquals(spec.core.maxSlope.toDouble(), curve.maxSlope(0.0, 1.0), 1e-6)
        for (sigma in listOf(1.0, 2.0 / 3)) for (ratio in listOf(.01, .2, .57, 1.0, 2.5)) {
            // Size independent (size exponent 0): every popup runs the one spring, Faster at 2/3 its duration.
            assertEquals(PopupSpringMs / 1000.0 * sigma, expansionDuration(spec, ratio * 1000, 1000.0, 0.0, 0.0, 1.0, sigma, 1.0 / 120), 1e-9)
        }
    }

    @Test fun popupLawReproducesTheV03321SpringPacingPeakAndEndAtEveryScaleSpeedAndSize() {
        for (scale in listOf(1f, .5f)) for ((access, speed) in listOf(MotionAccess.Standard to 4f / 3, MotionAccess.Faster to 2f)) for (width in listOf(150f, 400f, 1100f)) EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f / 120, 1000f, access, scale), StockUiSpec)
            val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            owner.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, width, 100f), 1f))
            owner.target(true)
            val start = h.clock.stampNow
            val spring = v03321Popup(speed)
            val case = "scale $scale ${access} width $width"
            fun engine(ms: Double) = owner.phase.valueAt(FrameStamp(start.nanos + (ms * 1e6).toLong(), start.sequence)).toDouble()
            // Compose's spring plays at real time / platform scale, as the engine does; it samples whole milliseconds of play time.
            fun reference(ms: Double) = spring.getValueFromNanos((ms * 1e6 / scale).toLong()).toDouble()
            val w = sqrt(90.0) * speed / scale
            fun analytic(ms: Double) = (w * ms / 1000).let { 1 - (1 + it) * exp(-it) }
            // Position, at every whole millisecond of Compose play time: within the spring's own .0001 settle threshold (the
            // normalisation) until the engine's 64 ms terminal bridge; inside it, between the spring and rest, never past 1.
            val step = scale.toDouble()
            val end = PopupSpringMs.toDouble() * (if (access == MotionAccess.Faster) 2.0 / 3 else 1.0) * scale
            val bridge = end - .064 * (if (access == MotionAccess.Faster) 2.0 / 3 else 1.0) * scale * 1000
            val at = (0..3000).map { it * step }.filter { it < bridge }.maxBy { abs(engine(it) - reference(it)) }
            val worst = abs(engine(at) - reference(at))
            assertTrue("$case position error $worst at $at ms: engine ${engine(at)} spring ${reference(at)}", worst <= 1e-4 / (1 - 1e-4) + 2 * Math.ulp(1f))
            for (ms in (0..3000).map { it * step }.filter { it >= bridge }) assertTrue("$case tail at $ms ms: engine ${engine(ms)} spring ${reference(ms)}", engine(ms) >= reference(ms) - 1e-6 && engine(ms) <= 1.0)
            for (i in 0 until (spring.durationNanos / 1_000_000).toInt()) assertEquals("$case Compose spring at $i ms", analytic(i * step), reference(i * step), 1e-6)
            // Pacing: 50, 66 and 90 % at the spring's time, to the 0.1 ms grid plus the normalisation's 1e-4 lift over the
            // spring's slope there; peak speed within the 1 / (1 - 1e-4) normalisation.
            for (fraction in listOf(.5, .66, .9)) {
                val t = crossing(fraction, ::analytic); val slope = w * w * t / 1000 * exp(-w * t / 1000)
                assertEquals("$case $fraction", t, crossing(fraction, ::engine), .1 + 1e-4 / (1 - 1e-4) / slope * 1000 + 1e-9)
            }
            val peak = (1..30000).maxOf { (engine(it / 10.0) - engine((it - 1) / 10.0)) * 1e4 }
            assertEquals("$case peak", w / E, peak, w / E * 2e-4)
            // End: the engine lands on 1 exactly where Compose's spring finishes (its .0001 threshold), to one 120 Hz frame.
            assertEquals("$case end", spring.durationNanos / 1e6 * scale, end, 1000.0 / 120)
            assertEquals(1.0, engine(end), 0.0)
            assertTrue("$case still moving", engine(end - 1) < 1.0)
            owner.dispose()
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
