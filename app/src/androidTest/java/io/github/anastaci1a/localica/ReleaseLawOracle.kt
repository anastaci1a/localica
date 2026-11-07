package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import kotlin.math.*

/** #600 Stage 2 / #608: shared release-law witness for every ExpandCollapse owner (player, Queue). Physical frames are
 * compared with an independent implementation of the #608 equations, not with the Engine's own plan. */
internal class Join(val nanos: Long, val before: Kinematic, val after: Kinematic, val target: Float, val policy: String, val release: Float?)
/** Records the joins of the owner whose phase key is [key] to "phase". */
internal class JoinRecorder(private val key: Any) : MotionEvents {
    val joins = mutableListOf<Join>()
    override fun created(motion: MotionFloat) {}
    override fun joined(motion: MotionFloat, join: MotionJoin) { if (motion.key == (key to "phase")) joins += Join(join.stamp.nanos, join.before, join.after, motion.target, join.policy, join.releaseVelocity) }
    override fun retired(motion: MotionFloat, stamp: FrameStamp) {}
}
internal class PhaseFrame(val nanos: Long, val p: Double)
/** Independent #608 oracle: fitted core, position/velocity duration law, C1 startup and terminal Hermite bridges. */
internal object Law {
    const val T = .426086832
    private fun bezier(u: Double): Pair<Double, Double> {
        val (x1, y1, x2, y2) = listOf(.242192, .999967, .511935, .997652)
        fun c(s: Double, a: Double, b: Double) = 3 * (1 - s) * (1 - s) * s * a + 3 * (1 - s) * s * s * b + s * s * s
        fun d(s: Double, a: Double, b: Double) = 3 * (1 - s) * (1 - s) * a + 6 * (1 - s) * s * (b - a) + 3 * s * s * (1 - b)
        var s = u; repeat(30) { s = (s - (c(s, x1, x2) - u) / max(d(s, x1, x2), 1e-9)).coerceIn(0.0, 1.0) }
        return c(s, y1, y2) to d(s, y1, y2) / max(d(s, x1, x2), 1e-9)
    }
    private fun hermite(t: Double, tau: Double, a: Double, va: Double, b: Double, vb: Double): Double {
        val u = t / tau; return (2 * u * u * u - 3 * u * u + 1) * a + (u * u * u - 2 * u * u + u) * tau * va + (-2 * u * u * u + 3 * u * u) * b + (u * u * u - u * u) * tau * vb
    }
    fun duration(p: Double, v: Double, q: Double) = max(T * (.500401615 + .499598385 * abs(q - p)) / max(1.0, abs(v) / 1.41952128).pow(.73607278), 2 / 120.0)
    fun at(p: Double, v: Double, q: Double, t: Double): Double {
        val d = duration(p, v, q); if (t >= d) return q
        fun core(s: Double) = bezier(s / d).let { (e, slope) -> p + (q - p) * e to (q - p) * slope / d }
        val tin = min(1 / 120.0, d / 4); val tout = min(d / 8, .064)
        return when {
            t < tin -> core(tin).let { (b, vb) -> hermite(t, tin, p, v, b, vb) }
            t > d - tout -> core(d - tout).let { (a, va) -> hermite(t - (d - tout), tout, a, va, q, 0.0) }
            else -> core(t).first
        }
    }
}
/** Every presented frame after an automatic join matches the oracle from that join; joins keep value and velocity. */
internal fun verifyReleaseLaw(label: String, frames: List<PhaseFrame>, joins: List<Join>, fallback: Join) {
    val automatic = joins.filter { it.policy != "DrivenInput" && it.policy != "Initial" }.ifEmpty { listOf(fallback) }
    for (j in joins.filter { it.policy == "Continuous" || it.policy == "DrivenRelease" }) {
        assertEquals("$label join value", j.before.value.toDouble(), j.after.value.toDouble(), 1e-4)
        if (j.policy == "Continuous") assertEquals("$label join velocity", j.before.velocity.toDouble(), j.after.velocity.toDouble(), 1e-3)
        else assertEquals("$label release hand-off is the accepted tracker velocity", j.release!!.toDouble(), j.after.velocity.toDouble(), 1e-4)
    }
    var worst = 0.0
    for (f in frames) {
        val j = automatic.lastOrNull { it.nanos <= f.nanos } ?: continue
        if (joins.any { it.policy == "DrivenInput" && it.nanos > j.nanos && it.nanos <= f.nanos }) continue
        val expected = Law.at(j.after.value.toDouble(), j.after.velocity.toDouble(), j.target.toDouble(), (f.nanos - j.nanos) / 1e9)
        worst = max(worst, abs(expected - f.p))
    }
    assertTrue("$label: presented phase deviates from the #608 law by $worst", worst <= .008)
}
internal fun fallbackJoin(nanos: Long, p: Double, v: Double, q: Boolean) = Join(nanos, Kinematic(p.toFloat(), v.toFloat()), Kinematic(p.toFloat(), v.toFloat()), if (q) 1f else 0f, "Fallback", null)
