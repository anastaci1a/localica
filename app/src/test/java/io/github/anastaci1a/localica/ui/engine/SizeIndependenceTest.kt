package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** #854 T3: popup timing does not depend on size. The phase is the same spring from 1 px to the maximum viewport diagonal, and each edge is start + delta G(phase). */
class SizeIndependenceTest {
    private val model = StockPopupMotion
    private val diagonal = 4000f
    private val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, diagonal, 1f / 30)) as SpecResult.Accepted).spec
    private val travels = listOf(1f, 10f, 100f, 1000f, diagonal)
    private val tolerance = 1e-5 * diagonal

    /** Samples the phase of a flight every frame while it is inside [window] seconds (analytic part), checking each edge against start + delta phase to 1e-5 range. */
    private fun record(rig: PopupRig, h: EngineHarness, hz: Int, window: Double, label: String): List<Pair<Double, Double>> {
        val start = h.clock.stampNow; val out = mutableListOf<Pair<Double, Double>>()
        while (true) {
            h.advance(1.0 / hz); val stamp = h.clock.stampNow; val t = (stamp.nanos - start.nanos) / 1e9
            if (t >= window) break
            val p = rig.owner.main.rawValue(stamp); out += t to p
            for (i in 0..3) assertEquals("$label edge $i at ${out.size}", rig.start[i] + (rig.end[i] - rig.start[i]) * p, rig.edge(i, stamp), tolerance)
        }
        assertTrue(label, out.size > 2)
        return out
    }
    private fun makeRig(h: EngineHarness, travel: Float, expanded: Boolean) =
        PopupRig(h, ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 100f + travel, 100f + travel / 2), 1f), PopupKind.Menu, expanded = expanded, span = diagonal * 2)

    @Test fun theSamePhaseAtEveryTravelAndTheEdgesAreAffineInIt() {
        for ((kind, expanded) in listOf("open" to false, "close" to true)) for (hz in listOf(60, 120)) {
            val l = model.law(PopupKind.Menu, if (expanded) PopupDirection.Close else PopupDirection.Open, false); val w = l.omega.toDouble(); val z = l.zeta.toDouble()
            val x = if (expanded) 1.0 else 0.0; val q = 1.0 - x
            var reference: List<Pair<Double, Double>>? = null
            for (travel in travels) EngineHarness().use { h ->
                h.setContext(hz)
                // Travel on x, half of it on y, so every edge but top and left moves.
                val rig = makeRig(h, travel, expanded); rig.owner.target(!expanded)
                val window = SpringOracle.window(listOf(doubleArrayOf(x, 0.0, q, w, z, 1.0)), 1.0, (1f / hz).toDouble())
                val flight = record(rig, h, hz, window, "$kind travel $travel hz $hz")
                for ((k, tp) in flight.withIndex()) assertEquals("$kind travel $travel frame $k", SpringOracle.at(x, 0.0, q, w, z, tp.first).first, tp.second, 1e-9)
                reference?.let { assertEquals("$kind travel $travel is the same trajectory as travel ${travels.first()}", it, flight) } ?: run { reference = flight }
                rig.owner.dispose()
            }
        }
    }

    @Test fun detachedWindowsFollowTheSameSpringAtEveryEnvelopeSize() {
        val open = model.law(PopupKind.Dialog, PopupDirection.Open, false); val w = open.omega.toDouble(); val z = open.zeta.toDouble(); val hz = 90
        for (size in listOf(100f, 1000f, 2500f, 4000f)) EngineHarness(spec = spec).use { h ->
            h.setContext(hz)
            val home = Rect(540f, 1200f, 540f, 1200f); val envelope = Rect(540f - size / 2, 1200f - size / 4, 540f + size / 2, 1200f + size / 4)
            val motion = PopupWindowMotion(h.clock, spec.expansion, "detached-$size", prepared = false, quiet = false, kind = PopupKind.Dialog)
            motion.show(true); motion.place(home, envelope, 1f, detached = true)
            assertTrue(motion.expansion.awaitingNativeGate)
            motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
            val rect = motion.bounds as MotionRect; val start = h.clock.stampNow
            val window = SpringOracle.window(listOf(doubleArrayOf(0.0, 0.0, 1.0, w, z, 1.0)), 1.0, (1f / hz).toDouble()); var k = 0
            val a = edgesOf(home); val b = edgesOf(envelope)
            while (true) {
                h.advance(1.0 / hz); val stamp = h.clock.stampNow; val t = (stamp.nanos - start.nanos) / 1e9; if (t >= window) break; k++
                val p = motion.expansion.main.rawValue(stamp)
                assertEquals("size $size frame $k", SpringOracle.fromRest(w, z, t), p, 1e-9)
                for (i in 0..3) assertEquals("size $size edge $i frame $k", a[i] + (b[i] - a[i]) * p, rect.channels[i].rawValue(stamp), tolerance)
            }
            assertTrue(k > 2); motion.dispose()
        }
    }
}
