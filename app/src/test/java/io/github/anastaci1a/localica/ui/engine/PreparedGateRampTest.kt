package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #854 T8: Curve.ramp(p0, b) is the C1 prepared-origin geometry (section 1.5), and a gate crossing with velocity stays C1 on the engine. */
class PreparedGateRampTest {
    private val model = StockPopupMotion
    private val p0 = model.preparedRamp; private val b = model.rampBlend
    private val ramp = Curve.ramp(p0, b)
    /** The slope above the blend: 1 / (1 - p0 - b/2). */
    private val gain = 1 / (1 - p0.toDouble() - b / 2.0)

    /** Closed form of section 1.5. */
    private fun value(p: Double): Double { val u = p - p0; return when { u <= 0 -> 0.0; u <= b -> u * u / (2 * b * (1 - p0 - b / 2.0)); else -> (u - b / 2.0) / (1 - p0 - b / 2.0) } }
    private fun slope(p: Double): Double { val u = p - p0; return when { u <= 0 -> 0.0; u <= b -> u / (b * (1 - p0 - b / 2.0)); else -> gain } }

    @Test fun rampMatchesItsClosedFormOverTheWholePhaseBand() {
        for (i in 0..2000) { val p = -.05 + 1.1 * i / 2000; val f = p.toFloat()
            assertEquals("value at $p", value(f.toDouble()), ramp.at(f).toDouble(), 2e-7); assertEquals("slope at $p", slope(f.toDouble()), ramp.slope(f).toDouble(), 2e-6) }
    }

    @Test fun rampIsConstantBelowP0EqualsOneAtOneAndIsNotClampedAbove() {
        for (i in 0 until 200) { val p = -.05f + (p0 + .05f) * i / 200; assertEquals("below p0 at $p", 0f, ramp.at(p), 0f); assertEquals(0f, ramp.slope(p), 0f) }
        assertEquals(1f, ramp.at(1f), 1e-6f)
        val kappa = springOvershoot(model.open.zeta.toDouble())
        assertEquals("the overshoot passes through", 1 + kappa * gain, ramp.at((1 + kappa).toFloat()).toDouble(), 1e-6)
        assertTrue(ramp.at(1.05f) > 1f); assertEquals(gain, ramp.slope(1.05f).toDouble(), 1e-6)
    }

    @Test fun rampIsC1AtBothJoinsAndItsSlopeNeverExceedsTheAffineGain() {
        val eps = 1e-5f
        for (join in listOf(p0, p0 + b)) {
            assertEquals("value continuous at $join", ramp.at(join - eps).toDouble(), ramp.at(join + eps).toDouble(), 2 * gain * eps + 1e-6)
            assertEquals("slope continuous at $join", ramp.slope(join - eps).toDouble(), ramp.slope(join + eps).toDouble(), 2 * gain * eps / b / (1 - p0 - b / 2.0) + 1e-6)
        }
        assertEquals("slope is 0 at p0", 0f, ramp.slope(p0), 0f); assertEquals("slope reaches the gain at p0 + b", gain, ramp.slope(p0 + b).toDouble(), 1e-5)
        assertTrue("no slope above the gain", (0..2000).all { ramp.slope(-.05f + 1.1f * it / 2000) <= gain + 1e-6 })
        // Second differences are bounded by the blend curvature 1 / (b span), so there is no kink anywhere.
        val h = 1e-3f; val curvature = 1 / (b * (1 - p0 - b / 2.0))
        for (i in 1 until 2000) { val p = -.04f + 1.08f * i / 2000
            assertTrue("kink at $p", abs(ramp.slope(p + h) - 2 * ramp.slope(p) + ramp.slope(p - h)) <= 2 * h * curvature + 1e-6) }
    }

    @Test fun rampRejectsInadmissibleParameters() {
        for ((a, c) in listOf(Float.NaN to .05f, .25f to Float.NaN, -.1f to .05f, .25f to 0f, .25f to -.05f, .96f to .05f, .5f to .5f)) {
            try { Curve.ramp(a, c); fail("ramp($a, $c) must be rejected") } catch (expected: IllegalArgumentException) {}
        }
    }

    /** Central differences of an edge's value equal its analytic velocity: a slope jump or a value jump anywhere would break it. */
    private fun assertEdgeC1(rig: PopupRig, from: FrameStamp, seconds: Double, label: String) {
        // Edges are Float channels: a central difference over 2h carries up to a few ulps of the edge value per 2h, so h is 1e-5 s
        // and that quantization is allowed on top; a slope jump at a join (gain times travel times phase speed) is orders larger.
        val h = 1e-5; var peak = 0.0
        val n = (seconds / 1e-4).toInt()
        val samples = (1 until n).map { k -> val t = k * 1e-4; val s = stampAt(from, t); val a = stampAt(from, t - h); val c = stampAt(from, t + h)
            // The stamps are whole nanoseconds, so divide by the real separation.
            Triple(t, (rig.edge(0, c) - rig.edge(0, a)) / ((c.nanos - a.nanos) / 1e9), requireNotNull(rig.rect).channels[0].rawVelocity(s)) }
        for ((_, _, v) in samples) peak = max(peak, abs(v))
        assertTrue("$label moves", peak > 1.0)
        val quantization = 4 * Math.ulp((0 until n).maxOf { abs(rig.edge(0, stampAt(from, it * 1e-4))) }.toFloat()) / (2 * h)
        for ((t, fd, v) in samples) assertEquals("$label velocity at t=$t", fd, v, 2e-4 * peak + quantization)
    }

    @Test fun aGateCrossingWithVelocityStaysC1OnTheEngine() {
        val basis = ExpansionBasis(Rect(300f, 300f, 400f, 400f), Rect(0f, 0f, 400f, 900f), 1f)
        for (hz in listOf(60, 120)) {
            EngineHarness().use { h -> // Opening through p0 = .25 with positive velocity.
                h.setContext(hz); val rig = PopupRig(h, basis, PopupKind.Menu, curve = ramp)
                rig.owner.target(true); assertEdgeC1(rig, h.clock.stampNow, .25, "open hz=$hz"); rig.owner.dispose()
            }
            EngineHarness().use { h -> // Reversing back across p0 with negative velocity.
                h.setContext(hz); val rig = PopupRig(h, basis, PopupKind.Menu, curve = ramp)
                rig.owner.target(true); var frames = 0
                while (rig.owner.main.rawValue(h.clock.stampNow) < .4 && frames < 600) { h.advance(1.0 / hz); frames++ }
                assertTrue(rig.owner.main.rawValue(h.clock.stampNow) >= .4)
                rig.owner.target(false); val start = h.clock.stampNow
                val crossing = (0..2000).first { rig.owner.main.rawValue(stampAt(start, it * 1e-4)) < p0 } * 1e-4
                assertTrue("the reversal crosses the gate value with velocity: ${rig.owner.main.rawVelocity(stampAt(start, crossing))}", rig.owner.main.rawVelocity(stampAt(start, crossing)) < -.1)
                assertEdgeC1(rig, start, crossing + .15, "reversal hz=$hz"); rig.owner.dispose()
            }
        }
    }

    @Test fun preparedWindowsRunTheRampAndOrdinaryWindowsStayLinear() {
        // PopupWindowMotion wires the model's ramp for a prepared spring owner, Curve.linear for ordinary owners and quiet row menus,
        // and a C1 ramp off QuietPopupHold for any other surface opened from a quiet anchor (lead ruling 2026-11-11, #692).
        val spec = (uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
        EngineHarness(spec = spec).use { h ->
            val prepared = PopupWindowMotion(h.clock, spec.expansion, "prepared", prepared = true, quiet = false, kind = PopupKind.Menu)
            val quiet = PopupWindowMotion(h.clock, spec.expansion, "quiet", prepared = false, quiet = true, kind = PopupKind.RowMenu)
            val plain = PopupWindowMotion(h.clock, spec.expansion, "plain", prepared = false, quiet = false, kind = PopupKind.Dialog)
            val quietRamp = Curve.ramp(QuietPopupHold, spec.popupMotion.rampBlend)
            val quietOthers = listOf(PopupKind.Dialog, PopupKind.Menu, PopupKind.BottomMenu, PopupKind.SelectionPill).map {
                PopupWindowMotion(h.clock, spec.expansion, "quiet-$it", prepared = false, quiet = true, kind = it) }
            for (i in 0..200) { val p = -.05f + 1.1f * i / 200
                assertEquals(ramp.at(p), prepared.geometryCurve.at(p), 0f); assertEquals(ramp.slope(p), prepared.geometryCurve.slope(p), 0f)
                assertEquals(prepared.geometryCurve.at(p), prepared.popupCurve.at(p), 0f)
                for (m in listOf(quiet, plain)) { assertEquals(p, m.geometryCurve.at(p), 1e-6f); assertEquals(1f, m.geometryCurve.slope(p), 0f) }
                for (m in quietOthers) { assertEquals(quietRamp.at(p), m.geometryCurve.at(p), 0f); assertEquals(quietRamp.slope(p), m.geometryCurve.slope(p), 0f)
                    assertEquals(p, m.popupCurve.at(p), 1e-6f) } }
            // The quiet hold: the edges stay on the trigger through the hold, leave C1 (slope 0 at the hold), and the return re-seats there.
            for (m in quietOthers) { assertEquals(0f, m.geometryCurve.at(QuietPopupHold), 0f); assertEquals(0f, m.geometryCurve.slope(QuietPopupHold), 0f)
                assertEquals(0f, m.geometryCurve.at(QuietPopupHold / 2), 0f); assertEquals(1f, m.geometryCurve.at(1f), 1e-6f)
                assertTrue("the hold's overshoot gain is admitted in the margin", m.overshoot > 0f && m.overshoot.isFinite()) }
            assertTrue(prepared.spring && quiet.spring && plain.spring && quietOthers.all { it.spring })
            for (m in listOf(prepared, quiet, plain) + quietOthers) m.dispose()
        }
    }
}
