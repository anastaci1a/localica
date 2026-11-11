package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * #854 T4: for the popup geometries (Sort/View, row menu, utility, anchored and detached dialog, selection pill; portrait and landscape)
 * every frame of open, reversal and close keeps anchored edges bit-constant, keeps every moving edge inside start-end widened by the
 * overshoot reach, and keeps the panel inside the window. Windows come from the section 1.7 placement margin with a zero inset.
 */
class AnchoredEdgeContainmentTest {
    private val model = StockPopupMotion
    private val kappa = springOvershoot(model.open.zeta.toDouble())
    private val density = 2.625f
    private class Scenario(val name: String, val kind: PopupKind, val home: Rect, val envelope: Rect, val prepared: Boolean)
    /** margin_axis = max(12 dp, kappa extent) at zero inset, in px. */
    private fun margin(extent: Float) = maxOf(12f * density, (kappa * extent).toFloat())

    private fun scenarios(w: Float, h: Float): List<Scenario> {
        val mx = margin(w); val my = margin(h); val cx = w / 2; val cy = h / 2
        val dialog = Rect(cx - 450, cy - 300, cx + 450, cy + 300)
        return listOf(
            Scenario("sort/view", PopupKind.Menu, Rect(w - mx - 200, my + 20, w - mx, my + 84), Rect(w - mx - 700, my + 20, w - mx, my + 560), true),
            Scenario("row menu", PopupKind.RowMenu, Rect(w - mx - 90, 400f, w - mx, 490f), Rect(w - mx - 500, 400f, w - mx, 800f), false),
            Scenario("utility", PopupKind.BottomMenu, Rect(w - mx - 150, h - my - 250, w - mx, h - my - 100), Rect(w - mx - 600, h - my - 700, w - mx, h - my - 100), false),
            Scenario("anchored dialog", PopupKind.Dialog, Rect(cx - 120, cy - 60, cx + 120, cy + 60), dialog, false),
            Scenario("detached dialog", PopupKind.Dialog, Rect(cx, cy, cx, cy), dialog, false),
            Scenario("selection pill", PopupKind.SelectionPill, Rect(mx, h - my - 200, w - mx, h - my - 100), Rect(w - mx - 250, h - my - 200, w - mx, h - my - 100), false),
        )
    }

    private fun run(sc: Scenario, w: Float, h: Float, hz: Int, s: Float) = EngineHarness().use { eh ->
        eh.setContext(hz, s)
        val label = "${sc.name} ${w.toInt()}x${h.toInt()} hz=$hz s=$s"
        val curve = if (sc.prepared) Curve.ramp(model.preparedRamp, model.rampBlend) else Curve.linear(0f, 1f)
        // How far past its end an edge reaches per unit of its travel at the largest reachable phase: kappa for an affine G.
        val reach = (curve.at((1 + kappa).toFloat()) - 1f).toDouble()
        val rig = PopupRig(eh, ExpansionBasis(sc.home, sc.envelope, density), sc.kind, curve = if (sc.prepared) curve else null)
        val phase = rig.owner.main; var n = 0
        fun frame() {
            eh.advance(1.0 / hz); n++; val stamp = eh.clock.stampNow; val p = phase.rawValue(stamp)
            assertTrue("$label frame $n phase $p", p >= -1e-9 && p <= 1 + kappa + 1e-9)
            val x = DoubleArray(4) { rig.edge(it, stamp) }
            for (i in 0..3) {
                val d = rig.end[i] - rig.start[i]
                if (d == 0.0) assertEquals("$label anchored edge $i frame $n", rig.start[i], x[i], 0.0)
                else {
                    val margin = abs(d) * reach + 1e-3
                    val lo = if (d > 0) rig.start[i] - 1e-3 else rig.end[i] - margin; val hi = if (d > 0) rig.end[i] + margin else rig.start[i] + 1e-3
                    assertTrue("$label edge $i frame $n: ${x[i]} outside $lo..$hi", x[i] >= lo && x[i] <= hi)
                }
            }
            assertTrue("$label frame $n inside the window: ${x.toList()}", x[0] >= -1e-3 && x[1] >= -1e-3 && x[2] <= w + 1e-3 && x[3] <= h + 1e-3)
            assertTrue("$label frame $n has a negative size: ${x.toList()}", x[0] <= x[2] + 1e-3 && x[1] <= x[3] + 1e-3)
        }
        fun runFor(seconds: Double) = repeat(max(1, (seconds * hz).roundToInt())) { frame() }
        // Open, a reversal at the open's peak speed, a reopen, then full open and close.
        rig.owner.target(true); runFor(.084 / s); rig.owner.target(false); runFor(.060 / s); rig.owner.target(true); runFor(3.0)
        val open = DoubleArray(4) { rig.edge(it, eh.clock.stampNow) }; for (i in 0..3) assertEquals("$label open rest edge $i", rig.end[i], open[i], 1e-3)
        rig.owner.target(false); runFor(3.0)
        for (i in 0..3) assertEquals("$label closed rest edge $i", rig.start[i], rig.edge(i, eh.clock.stampNow), 0.0)
        assertTrue(label, rig.owner.retirementReady)
        rig.owner.dispose()
    }

    @Test fun everyGeometryStaysAnchoredContainedAndInsideTheWindowInPortraitAndLandscape() {
        for ((w, h) in listOf(1080f to 2400f, 2400f to 1080f)) for (sc in scenarios(w, h)) for (hz in listOf(60, 120)) for (s in listOf(.5f, 1f, 1.5f)) run(sc, w, h, hz, s)
    }

    @Test fun placementMarginIsTwelveDpAcrossPortraitAndGrowsOnlyWhereTheReachNeedsIt() {
        // Section 1.7 with a zero inset: the margin is a no-op across portrait width and grows in landscape.
        assertEquals(12f * density, margin(1080f), 0f)
        assertTrue(margin(2400f) > 12f * density)
        assertEquals(kappa * 2400f, margin(2400f).toDouble(), 1e-3)
    }

    @Test fun aPreparedOriginReachesPastItsEndByTheRampGainTimesKappa() {
        // G(1 + kappa) - 1 = kappa / (1 - p0 - b/2) for the ramp, so the panel margin must use this reach, not kappa alone.
        val ramp = Curve.ramp(model.preparedRamp, model.rampBlend); val span = 1 - model.preparedRamp.toDouble() - model.rampBlend / 2.0
        assertEquals(kappa / span, (ramp.at((1 + kappa).toFloat()) - 1f).toDouble(), 1e-6)
        assertTrue(kappa / span > kappa)
    }
}
