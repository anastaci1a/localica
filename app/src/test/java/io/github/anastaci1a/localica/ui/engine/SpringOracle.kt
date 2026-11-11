package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*

/** Closed forms and harness helpers for the #854 popup motion tests. The spring is written from its ODE, independent of [Damped]. */
internal object SpringOracle {
    /** (value, velocity) at [t] of the damped spring from state ([x], [v]) toward [q] with natural frequency [w] and damping ratio [z]. */
    fun at(x: Double, v: Double, q: Double, w: Double, z: Double, t: Double): Pair<Double, Double> {
        val o = x - q
        return when {
            z < 1 -> { val a = z * w; val wd = w * sqrt(1 - z * z); val c = (v + a * o) / wd; val e = exp(-a * t); val s = sin(wd * t); val k = cos(wd * t)
                (q + e * (o * k + c * s)) to (e * (v * k - (a * c + o * wd) * s)) }
            z == 1.0 -> { val e = exp(-w * t); (q + e * (o + (v + w * o) * t)) to (e * (v - w * (v + w * o) * t)) }
            else -> { val r = sqrt(z * z - 1); val r1 = w * (-z + r); val r2 = w * (-z - r); val a = (v - r2 * o) / (r1 - r2); val b = o - a
                (q + a * exp(r1 * t) + b * exp(r2 * t)) to (a * r1 * exp(r1 * t) + b * r2 * exp(r2 * t)) }
        }
    }
    fun fromRest(w: Double, z: Double, t: Double) = at(0.0, 0.0, 1.0, w, z, t).first
    /** exp(-pi z / sqrt(1 - z^2)) for z < 1, else 0. */
    fun kappa(z: Double) = if (z >= 1) 0.0 else exp(-PI * z / sqrt(1 - z * z))
    /** First time the spring from rest toward 1 reaches [fraction] (monotone up to its first peak). */
    fun crossing(fraction: Double, w: Double, z: Double): Double {
        var lo = 0.0; var hi = if (z < 1) PI / (w * sqrt(1 - z * z)) else 1.0
        if (z >= 1) while (fromRest(w, z, hi) < fraction) hi *= 2
        repeat(200) { val mid = (lo + hi) / 2; if (fromRest(w, z, mid) < fraction) lo = mid else hi = mid }
        return hi
    }
    /** The end of the closed-form window of one intent: the shared finish minus the terminal bridge. Each state is x, v, q, omega_eff, zeta, range. */
    fun window(states: List<DoubleArray>, sigma: Double, display: Double): Double = plan(states, sigma, display).let { it.finish - it.tauOut }
    fun plan(states: List<DoubleArray>, sigma: Double, display: Double) = SpringPlan.of(FrameStamp(0L, 0L), sigma, display, states)
}

internal fun EngineHarness.setContext(hz: Int, speed: Float = 1f, scale: Float = 1f, access: MotionAccess = MotionAccess.Standard) =
    clock.configure(EngineContext(1f / hz, 1000f, access, scale, speed), clock.spec)

/** The published phase of a popup owner is its main phase. */
internal val ExpandCollapse.main: MotionFloat get() = phase as MotionFloat

/** The private cross-axis snap phase (#854 section 1.6), null for a Base or Legacy owner. */
internal fun ExpandCollapse.crossPhase(): MotionFloat? =
    ExpandCollapse::class.java.getDeclaredField("cross").apply { isAccessible = true }.get(this) as MotionFloat?

internal fun stampAt(stamp: FrameStamp, seconds: Double) = FrameStamp(stamp.nanos + Math.round(seconds * 1e9), stamp.sequence)

internal fun edgesOf(r: Rect) = doubleArrayOf(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble())

/** A popup owner (optionally with a bound rectangle) on [model] and [kind], at rest closed or open. */
internal class PopupRig(h: EngineHarness, val basis: ExpansionBasis, kind: PopupKind, expanded: Boolean = false, model: PopupMotionModel = StockPopupMotion,
    curve: Curve? = null, bound: Boolean = true, gate: Float? = null, span: Float = 4000f) {
    val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, expanded).also { it.usePopupLaw(model, kind); if (gate != null) it.nativeGate(gate); it.setBasis(basis) }
    val rect: MotionRect? = if (!bound) null else MotionRect(h.clock, if (expanded) basis.endBounds else basis.startBounds, EdgeRanges(span, span, span, span)).also {
        if (curve == null) owner.bind(it) else owner.bind(it, curve) }
    val start = edgesOf(basis.startBounds); val end = edgesOf(basis.endBounds)
    fun edge(i: Int, stamp: FrameStamp) = requireNotNull(rect).channels[i].rawValue(stamp)
}
