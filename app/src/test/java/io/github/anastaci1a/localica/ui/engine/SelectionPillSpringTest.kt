package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** #854 T9: the selection pill minimises on the close law and restores on the open law; its constant-height edges are anchored. */
class SelectionPillSpringTest {
    private val model = StockPopupMotion
    private val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
    /** The expanded panel and its minimised pill share top and bottom (the height is constant), so only left and right move. */
    private val minimised = Rect(300f, 2100f, 580f, 2200f); private val expandedPanel = Rect(100f, 2100f, 980f, 2200f)
    private val basis = ExpansionBasis(minimised, expandedPanel, 1f)

    @Test fun minimiseRunsTheCloseLawRestoreRunsTheOpenLawAndTheHeightEdgesNeverMove() {
        for (hz in listOf(60, 120)) for (s in listOf(.5f, 1f, 1.5f)) EngineHarness(spec = spec).use { h ->
            h.setContext(hz, s)
            val label = "hz=$hz s=$s"
            val motion = PopupMotion(h.clock, spec.expansion, "pill-$hz-$s", initial = true, kind = PopupKind.SelectionPill)
            motion.place(basis)
            val e = motion.expansion; val rect = requireNotNull(motion.bounds)
            assertEquals(PopupVariant.Base, e.popupLaw?.variant)
            assertEquals(PopupKind.SelectionPill, e.popupLaw?.kind)
            val start = edgesOf(minimised); val end = edgesOf(expandedPanel)
            fun fly(name: String, x: Double, q: Double, law: SpringLaw) {
                val w = law.omega * s.toDouble(); val z = law.zeta.toDouble(); val t0 = h.clock.stampNow
                val window = SpringOracle.window(listOf(doubleArrayOf(x, 0.0, q, w, z, 1.0)), 1.0 / s, (1f / hz).toDouble()); var frames = 0
                while (true) {
                    h.advance(1.0 / hz); val stamp = h.clock.stampNow; val t = (stamp.nanos - t0.nanos) / 1e9; if (t >= window) break; frames++
                    val p = e.main.rawValue(stamp); assertEquals("$label $name frame $frames", SpringOracle.at(x, 0.0, q, w, z, t).first, p, 1e-9)
                    for (i in 0..3) {
                        val edge = rect.channels[i].rawValue(stamp)
                        if (start[i] == end[i]) assertEquals("$label $name height edge $i frame $frames is anchored", start[i], edge, 0.0)
                        else assertEquals("$label $name edge $i frame $frames", start[i] + (end[i] - start[i]) * p, edge, 1e-5 * 4000)
                    }
                }
                assertTrue("$label $name flew", frames > 2)
                h.advance(5.0); assertTrue("$label $name settles", e.retirementReady)
            }
            // Minimise: expanded (phase 1) toward 0 on the close law.
            motion.show(false); fly("minimise", 1.0, 0.0, model.law(PopupKind.SelectionPill, PopupDirection.Close, false))
            // Restore: 0 toward 1 on the open law.
            motion.show(true); fly("restore", 0.0, 1.0, model.law(PopupKind.SelectionPill, PopupDirection.Open, false))
            assertEquals("The pill keeps its basis table kind", model.variant(PopupKind.SelectionPill), PopupVariant.Base)
            e.dispose()
        }
    }

    @Test fun theMinimiseAndRestoreLawsAreTheModelsCloseAndOpen() {
        assertEquals(model.close, model.law(PopupKind.SelectionPill, PopupDirection.Close, false))
        assertEquals(model.open, model.law(PopupKind.SelectionPill, PopupDirection.Open, false))
        // Neither law overshoots past the pill on minimise: the close law is critically damped or heavier.
        assertTrue(model.close.zeta >= 1f)
    }
}
