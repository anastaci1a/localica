package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * #854 T6: at every intent stamp (target, Rebase, gate acknowledgment, speed change) the phase, the cross phase, every edge and every
 * registered Curve consumer keep value and velocity to 1e-6 range, and all channels of the intent finish on the same frame.
 */
class SpringRetargetContinuityTest {
    private val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
    private val home = Rect(40f, 50f, 88f, 98f)
    private val envelope = Rect(10f, 20f, 310f, 620f)
    private val range = 4000.0
    private class Case(val name: String, val prepared: Boolean, val quiet: Boolean, val kind: PopupKind)
    private val cases = listOf(Case("prepared menu", true, false, PopupKind.Menu), Case("row menu", false, true, PopupKind.RowMenu),
        Case("bottom menu", false, false, PopupKind.BottomMenu), Case("dialog", false, false, PopupKind.Dialog))

    private fun curves(m: PopupWindowMotion) = listOf(m.geometryCurve, m.popupCurve, m.quietStrength, m.quietContent, m.foreground, m.detachedOpacity, m.homeWeight, m.face, m.preparationCurve)

    @Test fun everyPopupCurveIsC1() {
        EngineHarness(spec = spec).use { h ->
            for (c in cases) {
                val m = PopupWindowMotion(h.clock, spec.expansion, "curves-${c.name}", c.prepared, c.quiet, c.kind)
                for ((index, curve) in curves(m).withIndex()) {
                    val n = 20000; val step = 1.1f / n; var previous = curve.slope(-.05f)
                    for (i in 1..n) { val p = -.05f + step * i; val s = curve.slope(p)
                        // A C1 curve's slope moves by at most a bounded rate (the steepest window has 6 / width^2 < 400); a kink moves it at once.
                        assertTrue("${c.name} curve $index slope jumps at $p: $previous to $s", abs(s - previous) <= 1000f * step); previous = s }
                    // Value and slope agree: a central difference of the value equals the slope everywhere.
                    for (i in 1 until 200) { val p = -.04f + i * 1.08f / 200; val h2 = 1e-3f
                        assertEquals("${c.name} curve $index at $p", (curve.at(p + h2) - curve.at(p - h2)) / (2 * h2), curve.slope(p), 5e-2f * (1f + abs(curve.slope(p)))) }
                }
                m.dispose()
            }
        }
    }

    @Test fun everyIntentKeepsEveryChannelC1AndFinishesTogether() {
        for (c in cases) for (hz in listOf(60, 90, 120)) for (seed in 0 until 12) EngineHarness(spec = spec).use { h ->
            h.setContext(hz); val r = Random(854_600L + seed * 31 + hz)
            val motion = PopupWindowMotion(h.clock, spec.expansion, "t6-${c.name}-$hz-$seed", c.prepared, c.quiet, c.kind)
            var shown = false; var env = envelope
            motion.show(true); shown = true; motion.place(home, env, 1f)
            val e = motion.expansion; val rect = motion.bounds as MotionRect
            val cross = e.crossPhase(); val channels = listOfNotNull(e.main, cross) + rect.channels.toList()
            val consumers = curves(motion)
            // Each entry is (value, tolerance): the phase and cross phase to 1e-6, an edge to 1e-6 of its range, a Curve consumer's value and slope times velocity to 1e-5.
            fun sample(stamp: FrameStamp): List<Pair<Double, Double>> = channels.flatMap { m -> val tol = if (m === e.main || m === cross) 1e-6 else 1e-6 * range; listOf(m.rawValue(stamp) to tol, m.rawVelocity(stamp) to tol) } +
                consumers.flatMap { curve -> val p = e.main.rawValue(stamp).toFloat(); listOf(curve.at(p).toDouble() to 1e-5, curve.slope(p) * e.main.rawVelocity(stamp) to 1e-5) }
            val label = "${c.name} hz=$hz seed=$seed"
            fun intent(name: String, op: () -> Unit) {
                val stamp = h.clock.stampNow; val before = sample(stamp); op(); val after = sample(stamp)
                for (i in before.indices) assertEquals("$label $name entry $i", before[i].first, after[i].first, before[i].second)
            }
            fun show() { if (cross == null || spec.popupMotion.admitsCross(cross.settled, !shown)) intent("show") { shown = !shown; motion.show(shown) } }
            fun finishTogether(name: String) {
                // Every channel of the last intent is on one plan: all settle on the same frame.
                var frames = 0
                while (channels.any { !it.settled } && frames < 1200) { h.advance(1.0 / hz); frames++; val flags = channels.map { it.settled }.distinct()
                    assertEquals("$label $name frame $frames: channels settle on different frames", 1, flags.size) }
            }
            repeat(14) {
                when (r.nextInt(6)) {
                    0 -> show()
                    1 -> intent("place") { env = env.copy(right = env.right + r.nextFloat() * 120 - 40, bottom = env.bottom + r.nextFloat() * 120 - 40); motion.place(home, env, 1f) }
                    2 -> intent("acknowledge") { if (e.awaitingNativeGate) e.acknowledgeNativeGate(e.nativeGeneration) }
                    3 -> intent("speed") { motion.updateSpeed(4f * (5 + r.nextInt(11)) / 30f) }
                    4 -> repeat(1 + r.nextInt(30)) { h.advance(1.0 / hz) }
                    else -> { repeat(1 + r.nextInt(10)) { h.advance(1.0 / hz) }; show() }
                }
                if (e.awaitingNativeGate && r.nextBoolean()) intent("acknowledge") { e.acknowledgeNativeGate(e.nativeGeneration) }
            }
            if (e.awaitingNativeGate) e.acknowledgeNativeGate(e.nativeGeneration)
            finishTogether("last intent"); motion.dispose()
        }
    }
}
