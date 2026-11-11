package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #854 T2: the popup phase is the analytic spring of its law, at every display rate, speed and clock scale. */
class SpringPhaseLawTest {
    private val model = StockPopupMotion
    private val basis = ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 400f, 100f), 1f)
    private val speeds = (5..15).map { it / 10f }
    private fun law(kind: String) = when (kind) {
        "open" -> model.law(PopupKind.Menu, PopupDirection.Open, false); "close" -> model.law(PopupKind.Menu, PopupDirection.Close, false)
        "gate" -> model.law(PopupKind.Menu, PopupDirection.Gate, false); else -> model.law(PopupKind.BottomMenu, PopupDirection.Open, true)
    }

    /** One flight of [kind] at [hz], speed [s] and clock [scale]: every frame inside the analytic window equals the closed form. */
    private fun sweep(kind: String, hz: Int, s: Float, scale: Float) = EngineHarness().use { h ->
        h.setContext(hz, s, scale)
        val label = "$kind hz=$hz s=$s scale=$scale"
        val x = if (kind == "close") 1.0 else 0.0; val q = when (kind) { "close" -> 0.0; "gate" -> model.preparedRamp.toDouble(); else -> 1.0 }
        // The cross law is observed through a cross edge (G is linear): top = start + delta phi_cross. The other kinds are observed on the phase.
        val cross = kind == "cross"
        val rig = if (cross) PopupRig(h, ExpansionBasis(Rect(0f, 100f, 100f, 200f), Rect(0f, 0f, 400f, 200f), 1f), PopupKind.BottomMenu)
            else PopupRig(h, basis, PopupKind.Menu, expanded = kind == "close", gate = if (kind == "gate") model.preparedRamp else null)
        rig.owner.target(kind != "close")
        val l = law(kind); val w = l.omega * s.toDouble(); val z = l.zeta.toDouble(); val display = (1f / hz).toDouble(); val sigma = 1.0 / s
        val states = listOf(doubleArrayOf(x, 0.0, q, w, z, 1.0)) + if (cross) listOf(doubleArrayOf(0.0, 0.0, 1.0, model.open.omega * s.toDouble(), model.open.zeta.toDouble(), 1.0)) else emptyList()
        val plan = SpringOracle.plan(states, sigma, display); val window = plan.finish - plan.tauOut
        val start = h.clock.stampNow; var frames = 0
        while (true) {
            h.advance(1.0 / hz); val stamp = h.clock.stampNow; val t = (stamp.nanos - start.nanos) / 1e9 / scale
            if (t >= window) break
            val expected = SpringOracle.at(x, 0.0, q, w, z, t).first
            if (cross) assertEquals("$label frame $frames", expected, (rig.edge(1, stamp) - 100.0) / -100.0, 1e-6)
            else assertEquals("$label frame $frames", expected, rig.owner.main.rawValue(stamp), 1e-9)
            frames++
        }
        assertTrue("$label ran $frames frames", frames > 2)
        h.advance(plan.finish * scale + 1.0)
        assertTrue(label, rig.owner.main.settled)
        assertEquals(label, q, rig.owner.main.rawValue(h.clock.stampNow), 0.0)
        rig.owner.dispose()
    }

    @Test fun phasePerFrameEqualsTheClosedFormForEveryLawDisplayRateAndSpeed() {
        for (kind in listOf("open", "close", "gate")) for (hz in listOf(60, 90, 120)) for (s in speeds) sweep(kind, hz, s, 1f)
        // The cross law is observed through its edges (a Float curve), at the speeds the snap runs at.
        for (hz in listOf(60, 90, 120)) for (s in listOf(.5f, 1f, 1.5f)) sweep("cross", hz, s, 1f)
    }

    @Test fun clockScaleStretchesTimeAndLeavesTheShape() {
        for (kind in listOf("open", "close", "gate")) for (scale in listOf(.5f, 2f)) for (s in listOf(.5f, 1f, 1.5f)) sweep(kind, 120, s, scale)
    }

    @Test fun theFlightEndsOnItsFinishFrameStretchedByTheClockScale() {
        for (scale in listOf(.5f, 1f, 2f)) for (s in listOf(.5f, 1f, 1.5f)) EngineHarness().use { h ->
            h.setContext(120, s, scale); val rig = PopupRig(h, basis, PopupKind.Menu, bound = false); rig.owner.target(true)
            val l = law("open"); val finish = SpringOracle.plan(listOf(doubleArrayOf(0.0, 0.0, 1.0, l.omega * s.toDouble(), l.zeta.toDouble(), 1.0)), 1.0 / s, (1f / 120).toDouble()).finish
            h.advance(finish * scale - .004); assertFalse("scale $scale s $s", rig.owner.main.settled)
            h.advance(.008); assertTrue("scale $scale s $s", rig.owner.main.settled)
            assertEquals(1.0, rig.owner.main.rawValue(h.clock.stampNow), 0.0)
            rig.owner.dispose()
        }
    }

    @Test fun overshootIsExactlyKappaAtPiOverOmegaD() {
        for (s in speeds) EngineHarness().use { h ->
            h.setContext(120, s); val rig = PopupRig(h, basis, PopupKind.Menu, bound = false); rig.owner.target(true)
            val l = law("open"); val z = l.zeta.toDouble(); val w = l.omega * s.toDouble(); val wd = w * sqrt(1 - z * z); val kappa = exp(-PI * z / sqrt(1 - z * z))
            assertEquals(kappa, springOvershoot(z), 1e-15)
            val start = h.clock.stampNow
            assertEquals("s=$s", 1 + kappa, rig.owner.main.rawValue(stampAt(start, PI / wd)), 1e-9)
            val peak = (0..4000).maxOf { rig.owner.main.rawValue(stampAt(start, it * 5e-4)) }
            assertTrue("s=$s peak $peak", peak <= 1 + kappa + 1e-9)
            assertEquals("s=$s", 1 + kappa, Damped(0.0, 0.0, 1.0, w, z, 1.0, 1.0 / 120).extrema.endInclusive, 1e-12)
            rig.owner.dispose()
        }
    }

    @Test fun closeAndGateLawsNeverOvershootFromRest() {
        for (s in speeds) for (kind in listOf("close", "gate")) EngineHarness().use { h ->
            h.setContext(120, s)
            val rig = PopupRig(h, basis, PopupKind.Menu, expanded = kind == "close", gate = if (kind == "gate") model.preparedRamp else null); rig.owner.target(kind != "close")
            val q = if (kind == "close") 0.0 else model.preparedRamp.toDouble(); val x = if (kind == "close") 1.0 else 0.0; val start = h.clock.stampNow
            val values = (0..4000).map { rig.owner.main.rawValue(stampAt(start, it * 5e-4)) }
            assertTrue("$kind s=$s", values.all { it >= min(x, q) - 1e-12 && it <= max(x, q) + 1e-12 })
            assertTrue("$kind s=$s is monotone", values.zipWithNext().all { (a, b) -> if (q > x) b >= a - 1e-12 else b <= a + 1e-12 })
            rig.owner.dispose()
        }
    }
}
