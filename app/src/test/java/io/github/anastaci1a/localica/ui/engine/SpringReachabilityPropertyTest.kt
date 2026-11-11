package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * #854 T5: random chains (open, close, Rebase, hold and release, native gate acknowledgment, speed changes in flight; 60, 90 and 120 Hz;
 * speeds 0.5 to 1.5; Base, CrossAxisSnap and Auto variants) keep the phase and the cross phase in [0, 1 + kappa], because the speed is
 * captured per flight. The chain count defaults to [DefaultChains]; set LOCALICA_T5_CHAINS=100000 for the full run.
 */
class SpringReachabilityPropertyTest {
    private val chains = System.getenv("LOCALICA_T5_CHAINS")?.toIntOrNull() ?: DefaultChains
    private val kinds = listOf(PopupKind.Menu, PopupKind.RowMenu, PopupKind.BottomMenu, PopupKind.Dialog, PopupKind.SelectionPill)
    private val autoModel = StockPopupMotion.copy(variants = StockPopupMotion.variants + (PopupKind.Menu to PopupVariant.Auto))
    private val kappa = springOvershoot(StockPopupMotion.open.zeta.toDouble())
    private val span = 4000f

    private fun randomBasis(r: Random): ExpansionBasis {
        fun rect(): Rect { val x = r.nextFloat() * 500; val y = r.nextFloat() * 900; return Rect(x, y, x + r.nextFloat() * 600, y + r.nextFloat() * 900) }
        return ExpansionBasis(rect(), rect(), 1f)
    }

    private fun runChain(h: EngineHarness, index: Int) {
        val r = Random(854_000L + index)
        val hz = listOf(60, 90, 120)[r.nextInt(3)]; var speed = (5 + r.nextInt(11)) / 10f
        h.setContext(hz, speed)
        val auto = r.nextInt(4) == 0; val kind = if (auto) PopupKind.Menu else kinds[r.nextInt(kinds.size)]; val model = if (auto) autoModel else StockPopupMotion
        val gated = r.nextInt(4) == 0; var basis = randomBasis(r)
        val rig = PopupRig(h, basis, kind, model = model, curve = if (gated) Curve.ramp(model.preparedRamp, model.rampBlend) else Curve.linear(0f, 1f), gate = if (gated) model.preparedRamp else null, span = span)
        val owner = rig.owner; val phase = owner.main; val cross = owner.crossPhase(); val log = StringBuilder("hz=$hz speed=$speed kind=$kind auto=$auto gated=$gated")
        fun note(op: String) { val s = h.clock.stampNow; log.append(" | $op p=${phase.rawValue(s)} v=${phase.rawVelocity(s)}") }
        fun frame() {
            h.advance(1.0 / hz); val stamp = h.clock.stampNow
            for (m in listOfNotNull(phase, cross)) { val p = m.rawValue(stamp); assertTrue("phase $p outside [0, ${1 + kappa}]; $log", p >= -1e-9 && p <= 1 + kappa + 1e-9) }
        }
        fun acknowledge() { if (owner.awaitingNativeGate) { note("ack"); owner.acknowledgeNativeGate(owner.nativeGeneration) } }
        repeat(4 + r.nextInt(9)) {
            when (r.nextInt(7)) {
                0, 1 -> { val q = r.nextBoolean(); note("target $q"); owner.target(q) }
                2 -> repeat(1 + r.nextInt(40)) { frame(); if (gated && r.nextInt(3) == 0) acknowledge() }
                3 -> { basis = when (r.nextInt(3)) { 0 -> randomBasis(r); 1 -> basis.copy(endBounds = basis.endBounds.copy(right = basis.endBounds.right + r.nextFloat() * 200)); else -> basis.copy(density = if (basis.density == 1f) 2f else 1f) }; note("basis"); owner.setBasis(basis) }
                4 -> {
                    // A speed change is never applied in flight: the trajectory continues exactly as planned, and the new speed waits for the next flight from rest.
                    val stamp = h.clock.stampNow; val ahead = stampAt(stamp, .01); val flying = !phase.settled
                    val p0 = phase.rawValue(stamp); val v0 = phase.rawVelocity(stamp); val pAhead = phase.rawValue(ahead); val remaining = abs(owner.main.target - p0)
                    val next = (5 + r.nextInt(11)) / 10f
                    if (r.nextBoolean()) { speed = next; h.setContext(hz, speed); owner.target(owner.expanded) } else owner.updateSpeed(4f * next / 3f)
                    assertEquals(p0, phase.rawValue(stamp), 1e-9); assertEquals(v0, phase.rawVelocity(stamp), 1e-6)
                    if (flying && remaining > .02) assertEquals("the flight keeps its captured speed", pAhead, phase.rawValue(ahead), 1e-9)
                }
                5 -> { note("hold"); owner.hold(); repeat(1 + r.nextInt(10)) { frame() }; owner.release() }
                else -> acknowledge()
            }
        }
        // Every chain comes to rest: acknowledge any gate and run out the longest flight at the slowest speed.
        repeat(900) { frame(); acknowledge() }
        assertTrue("the chain did not settle", owner.retirementReady)
        owner.dispose()
    }

    @Test fun randomChainsStayInTheBandBecauseTheSpeedIsCapturedPerFlight() = EngineHarness().use { h ->
        for (i in 0 until chains) try { runChain(h, i) } catch (e: Throwable) { throw AssertionError("chain $i (seed ${854_000L + i}) of $chains: ${e.message}", e) }
    }

    @Test fun dampedExtremaAreExactForEveryReachableState() {
        val r = Random(854_001L)
        repeat(3000) { i ->
            val z = listOf(.5, .6, .75, .9, 1.0, 1.0, 1.5, 2.5, 4.0)[r.nextInt(9)]; val w = 1 + r.nextDouble() * 199
            val x = (r.nextDouble() * 2.1 - 1.05); val v = (r.nextDouble() * 2.2 - 1.1) * w; val q = if (r.nextBoolean()) 1.0 else 0.0
            val d = Damped(x, v, q, w, z, 1.0, 1.0 / 120); val e = d.extrema; val n = 4000; val step = d.duration / n
            val values = (0..n).map { d.value(step * it) }
            // Refine the sampled extremes on a fine local grid, so the comparison is not limited by the sampling step.
            fun refined(sign: Double): Double {
                val k = values.indices.maxBy { sign * values[it] }; val t0 = max(0.0, (k - 1) * step); val t1 = min(d.duration, (k + 1) * step)
                return (0..400).maxOf { sign * d.value(t0 + (t1 - t0) * it / 400) }.let { max(it, sign * values[k]) } * sign
            }
            val high = refined(1.0); val low = refined(-1.0); val label = "state $i (x=$x, v=$v, q=$q, w=$w, z=$z)"
            assertTrue("$label: sampled $high above ${e.endInclusive}", high <= e.endInclusive + 1e-9)
            assertTrue("$label: sampled $low below ${e.start}", low >= e.start - 1e-9)
            assertEquals("$label: maximum is reached", e.endInclusive, max(high, x), 1e-7 * (1 + abs(x - q) + abs(v) / w))
            assertEquals("$label: minimum is reached", e.start, min(low, x), 1e-7 * (1 + abs(x - q) + abs(v) / w))
        }
    }

    /** The worst peak over every switch time (ms grid) of a spring at speed [s1] that is re-planned at speed [s2] toward the same target. */
    private fun replanPeak(s1: Double, s2: Double): Pair<Double, Double> {
        val open = StockPopupMotion.open; val w = open.omega.toDouble(); val z = open.zeta.toDouble(); var worst = 0.0; var at = 0.0
        for (i in 0..600) {
            val t1 = i * .001; val (x, v) = SpringOracle.at(0.0, 0.0, 1.0, w * s1, z, t1)
            val peak = (0..3000).maxOf { SpringOracle.at(x, v, 1.0, w * s2, z, it * .001).first }
            if (peak > worst) { worst = peak; at = t1 }
        }
        return worst to at
    }

    @Test fun redWitnessesShowWhyTheSpeedIsCaptured() {
        val band = StockPopupExpansionBand.toDouble()
        // At one speed, a time-scaled copy of the same spring: the peak is kappa at every switch time and every speed.
        for (s in listOf(.5, 1.0, 1.5)) assertTrue("uniform $s", replanPeak(s, s).first <= 1 + kappa + 1e-9)
        // Re-planning the open from Faster (1.5) to standard leaves the band (1.0706 on the pass-1 law, 1.0549 on 855-ios262-60fps-interim).
        val faster = replanPeak(1.5, 1.0); assertTrue("Faster to standard ${faster.first}", faster.first > 1 + band)
        // Open at 1.5 re-planned at 0.5 leaves it further (1.274 on the pass-1 law).
        val chain = replanPeak(1.5, .5); assertTrue("1.5 to 0.5 ${chain.first}", chain.first > faster.first)
        // A close in flight at 1.5, re-planned at 0.5, drops below zero past the band on the close side (worst over the switch times of both stages).
        val open = StockPopupMotion.open; val close = StockPopupMotion.close; var undershoot = 0.0
        for (i in 0..25) for (j in 0..30) {
            val (x, v) = SpringOracle.at(0.0, 0.0, 1.0, open.omega * 1.5, open.zeta.toDouble(), .2 + i * .004)
            val (x2, v2) = SpringOracle.at(x, v, 0.0, close.omega * 1.5, close.zeta.toDouble(), j * .004)
            undershoot = min(undershoot, (0..1500).minOf { SpringOracle.at(x2, v2, 0.0, close.omega * .5, close.zeta.toDouble(), it * .002).first })
        }
        assertTrue("close side undershoot $undershoot", undershoot < -band)
    }

    @Test fun theEngineKeepsTheCapturedSpeedWhereReplanningWouldLeaveTheBand() = EngineHarness().use { h ->
        val open = StockPopupMotion.open; val z = open.zeta.toDouble(); val w = open.omega.toDouble(); val switch = replanPeak(1.5, 1.0).second // the worst switch time for this law
        h.setContext(120, 1.5f)
        val rig = PopupRig(h, ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 400f, 100f), 1f), PopupKind.Menu, bound = false); val owner = rig.owner
        owner.target(true); val start = h.clock.stampNow
        h.advance(switch); h.setContext(120, 1.0f); owner.target(true); owner.updateSpeed(4f / 3)
        // The flight keeps omega = 1.5 w to its end; the value at the switch is the Faster spring's, so a re-plan at omega = w would leave the band instead.
        val (xs, vs) = SpringOracle.at(0.0, 0.0, 1.0, w * 1.5, z, (h.clock.stampNow.nanos - start.nanos) / 1e9)
        assertEquals(xs, owner.main.rawValue(h.clock.stampNow), 1e-9); assertEquals(vs, owner.main.rawVelocity(h.clock.stampNow), 1e-6)
        val replan = (0..3000).maxOf { SpringOracle.at(xs, vs, 1.0, w, z, it * .001).first }
        assertTrue("a re-plan would peak at $replan", replan > 1 + StockPopupExpansionBand)
        val window = SpringOracle.window(listOf(doubleArrayOf(0.0, 0.0, 1.0, w * 1.5, z, 1.0)), 1.0 / 1.5, 1.0 / 120)
        for (k in 0..200) { val t = (h.clock.stampNow.nanos - start.nanos) / 1e9 + k * .004; if (t >= window) break
            val p = owner.main.rawValue(stampAt(h.clock.stampNow, k * .004)); assertEquals("t=$t", SpringOracle.fromRest(w * 1.5, z, t), p, 1e-9); assertTrue(p <= 1 + kappa + 1e-9) }
        // The next flight from rest uses the new speed.
        h.advance(5.0); assertTrue(owner.main.settled)
        owner.target(false); val closeStart = h.clock.stampNow; val close = StockPopupMotion.close
        for (k in 1..60) assertEquals("close $k", SpringOracle.at(1.0, 0.0, 0.0, close.omega.toDouble(), close.zeta.toDouble(), k * .004).first, owner.main.rawValue(stampAt(closeStart, k * .004)), 1e-9)
        owner.dispose()
    }

    companion object { const val DefaultChains = 10_000 }
}
