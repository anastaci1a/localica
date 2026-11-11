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
        for (scale in listOf(1f, .5f)) for ((root, speed) in listOf(1f to 4f / 3, 1.5f to 2f)) for (width in listOf(150f, 400f, 1100f)) EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, scale, root), StockUiSpec)
            val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            owner.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, width, 100f), 1f))
            owner.target(true)
            val start = h.clock.stampNow
            val spring = v03321Popup(speed)
            val case = "scale $scale root $root width $width"
            fun engine(ms: Double) = owner.phase.valueAt(FrameStamp(start.nanos + (ms * 1e6).toLong(), start.sequence)).toDouble()
            // Compose's spring plays at real time / platform scale, as the engine does; it samples whole milliseconds of play time.
            fun reference(ms: Double) = spring.getValueFromNanos((ms * 1e6 / scale).toLong()).toDouble()
            val w = sqrt(90.0) * speed / scale
            fun analytic(ms: Double) = (w * ms / 1000).let { 1 - (1 + it) * exp(-it) }
            // Position, at every whole millisecond of Compose play time: within the spring's own .0001 settle threshold (the
            // normalisation) until the engine's 64 ms terminal bridge; inside it, between the spring and rest, never past 1.
            val step = scale.toDouble()
            val end = PopupSpringMs.toDouble() * (1.0 / root) * scale
            val bridge = end - .064 * (1.0 / root) * scale * 1000
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

    /** #608 measured drawer model (feedback/issues/608-expand-collapse-motion/analysis/REPORT.md): the player sheet and the
     * Queue drawer (operator 2026-11-11 3:17 and 3:20 AM). Its numbers are 1x (animator scale 1.0) references. */
    private val drawer608Core = EasingSpec(.242192f, .999967f, .511935f, .997652f)
    /** #608 object time: 426.086832 ms x (travel / full-player travel)^.5, floored at 140 ms; the full player is exactly 426.1 ms. */
    private fun drawer608ObjectTime(ratio: Double) = max(.14, .426086832 * sqrt(ratio))
    private fun drawer608Duration(remaining: Double, ratio: Double = 1.0) = drawer608ObjectTime(ratio) * (.500401615 + .499598385 * remaining)
    /** The harness reference travel is 1000 px: the player's edge travel equals it; the Queue's top travel is longer (#608: 1.06x here). */
    private val drawers = listOf("player" to 1000f, "Queue" to 1060f)
    private fun drawerOwner(h: EngineHarness, travel: Float) = ExpandCollapse(h.clock, StockUiSpec.sheetExpansion, false).also {
        it.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 100f + travel, 100f), 1f))
    }

    @Test fun drawerLawIsThe608MeasuredModelForAutomaticOpenAndCloseAtOneAndHalfScale() {
        val spec = StockUiSpec.sheetExpansion
        assertEquals(drawer608Core, spec.core)
        assertEquals(426.086832f, spec.referenceDurationMs, 0f)
        for ((name, travel) in drawers) for (sigma in listOf(1.0, 2.0 / 3)) for ((p, q) in listOf(0.0 to 1.0, 1.0 to 0.0)) {
            val ratio = travel / 1000.0
            val duration = expansionDuration(spec, travel.toDouble(), 1000.0, p, 0.0, q, sigma, 1.0 / 120)
            // The 1x reference (exactly 426.1 ms for the full player), to the Float storage of 426.086832 ms (one ulp is 3.1e-5 ms).
            assertEquals("$name sigma $sigma", drawer608ObjectTime(ratio) * sigma, duration, Math.ulp(426.086832f) / 1000.0 * sqrt(ratio))
            // Shape: between the C1 startup and terminal bridges the flight is exactly the #608 Bezier E(t / T).
            val law = Eased(p, 0.0, q, spec.core, duration, sigma)
            val curve = unitCurve(drawer608Core)
            for (i in 0..1000) { val t = duration * i / 1000; if (t >= law.tauIn && t < duration - law.tauOut) assertEquals(p + (q - p) * curve.at(t / duration), law.value(t), 1e-12) }
        }
        // A real owner from rest follows that flight. The platform scale applies as to every engine law:
        // real time = model time x scale, so the full player lands at 426.09 ms at 1x and 213.04 ms at 0.5x.
        for ((name, travel) in drawers) for (scale in listOf(1f, .5f)) for (open in listOf(true, false)) EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, scale), StockUiSpec)
            val c = drawerOwner(h, travel)
            if (!open) { c.target(true); h.advance(1.0) }
            c.target(open); val start = h.clock.stampNow
            fun at(ms: Double) = c.phase.valueAt(FrameStamp(start.nanos + (ms * 1e6).toLong(), start.sequence)).toDouble()
            val model = drawer608ObjectTime(travel / 1000.0)
            val law = Eased(if (open) 0.0 else 1.0, 0.0, if (open) 1.0 else 0.0, drawer608Core, model, 1.0)
            val end = model * 1000 * scale
            for (i in 0..(model * 1000).toInt()) { val ms = i * scale.toDouble(); assertEquals("$name scale $scale open $open at $ms ms", law.value(ms / scale / 1000.0), at(ms), 1e-6) }
            assertTrue("$name scale $scale open $open still moving 1 ms before the end", at(end - 1) != if (open) 1.0 else 0.0)
            assertEquals(if (open) 1.0 else 0.0, at(end + .001), 0.0)
            c.dispose()
        }
    }

    @Test fun drawerNearStationaryReleasesFollowThe608PositionLawAtSeveralPositionsAndScales() {
        // #608 examples (full player, 1x): remaining 10.5 % -> 235.6 ms, 30.6 % -> 278.4 ms, 48.1 % -> 315.6 ms.
        assertEquals(235.6, drawer608Duration(.105) * 1000, .05); assertEquals(278.4, drawer608Duration(.306) * 1000, .05); assertEquals(315.6, drawer608Duration(.481) * 1000, .05)
        // Stationary and inside the .25 travel/s dead band (0.1/s either way): open iff p >= .5; T from the remaining fraction;
        // the hand-off keeps the measured velocity. At 0.5x the same decision lands in half the real time.
        for ((name, travel) in drawers) for (scale in listOf(1f, .5f)) for (p in listOf(.105, .306, .481, .532, .694, .895)) for (velocity in listOf(0.0, .1, -.1)) EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, scale), StockUiSpec)
            val c = drawerOwner(h, travel)
            c.contactBegin(h.clock.stampNow); h.now += 10_000_000; c.contactMove((p * travel).toFloat(), h.clock.stampNow)
            val p0 = c.phase.valueAt(h.clock.stampNow).toDouble()
            assertEquals(p, p0, 1e-6)
            val decision = c.contactRelease((velocity * travel).toFloat(), h.clock.stampNow)
            val open = p0 >= .5
            val case = "$name scale $scale p $p v $velocity"
            assertEquals(case, open, decision.expanded)
            val model = drawer608Duration(if (open) 1 - p0 else p0, travel / 1000.0)
            assertEquals(case, model, decision.durationSeconds.toDouble(), 1e-6)
            assertEquals("$case hand-off keeps the measured velocity", velocity, decision.handOffVelocity.toDouble(), 1e-6)
            val released = h.clock.stampNow
            fun at(ms: Double) = c.phase.valueAt(FrameStamp(released.nanos + (ms * 1e6).toLong(), released.sequence))
            assertEquals(case, if (open) 1f else 0f, at(model * 1000 * scale + .001), 0f)
            assertTrue("$case still moving 1 ms before model time x scale", at(model * 1000 * scale - 1) != if (open) 1f else 0f)
            c.dispose()
        }
        // A fling beyond the knee follows its direction and shortens by (|v| / 1.41952128)^.73607278 (#608 velocity law).
        for ((name, travel) in drawers) for ((p, velocity) in listOf(.4 to 3.0, .7 to -3.0)) EngineHarness().use { h ->
            val c = drawerOwner(h, travel)
            c.contactBegin(h.clock.stampNow); h.now += 10_000_000; c.contactMove((p * travel).toFloat(), h.clock.stampNow)
            val p0 = c.phase.valueAt(h.clock.stampNow).toDouble()
            val decision = c.contactRelease((velocity * travel).toFloat(), h.clock.stampNow)
            assertEquals("$name $p", velocity > 0, decision.expanded)
            val ratio = travel / 1000.0
            val reference = abs(velocity) * drawer608ObjectTime(ratio) / .426086832
            assertEquals("$name $p", drawer608Duration(if (velocity > 0) 1 - p0 else p0, ratio) / max(1.0, reference / 1.41952128).pow(.73607278), decision.durationSeconds.toDouble(), 1e-6)
            c.dispose()
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
