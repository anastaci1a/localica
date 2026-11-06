package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import kotlin.math.*
import kotlin.random.Random

/** Stage0 registers independent reference fixtures. Production factory coverage starts in U12.01b. */
internal class CurveRegistry(private val owner: OwnerThread = OwnerThread()) {
    private data class Entry(val curve: Curve, val joins: FloatArray)
    private val curves = linkedMapOf<String, Entry>()
    fun register(id: String, curve: Curve, joins: FloatArray) {
        owner.check(); require(id.isNotBlank() && joins.all { it.isFinite() && it in -.05f..1.05f })
        val existing = curves[id]
        require(existing == null || existing.curve === curve && existing.joins.contentEquals(joins))
        if (existing == null) curves[id] = Entry(curve, joins.copyOf())
    }
    fun verify(seed: Long, randomPointsPerCurve: Int = 256): List<Violation> {
        owner.check(); require(randomPointsPerCurve in 1..10_000)
        val random = Random(seed); val result = mutableListOf<Violation>()
        curves.entries.forEachIndexed { index, (id, entry) ->
            val curve = entry.curve
            fun bad(code: String, p: Double) { result.add(Violation("I2", index + 1L, 0, code, "$id p=$p seed=$seed")) }
            val points = List(randomPointsPerCurve) { random.nextDouble(-.05, 1.05) } + listOf(-.05, 1.05) + entry.joins.map { it.toDouble() }
            for (p in points) {
                val value = curve.at(p); val slope = curve.slope(p)
                if (!value.isFinite() || !slope.isFinite()) { bad("NonFiniteCurve", p); continue }
                val boundaries = entry.joins.map { it.toDouble() } + listOf(-.05,1.05)
                val distance = boundaries.map { abs(it-p) }.filter { it > 1e-12 }.minOrNull() ?: 1.0
                val step = min(1e-3,distance/4)
                val atJoin = boundaries.any { abs(it-p) <= 1e-12 }
                val directions = if (atJoin) listOf(-1,1).filter { p+it*2*step >= -.05000001 && p+it*2*step <= 1.05000001 } else listOf(0)
                for (direction in directions) {
                    fun derivative(h: Double): Double = if (direction == 0) (curve.at(p+h)-curve.at(p-h))/(2*h)
                        else (-3*value+4*curve.at(p+direction*h)-curve.at(p+direction*2*h))/(2*direction*h)
                    val a = derivative(step); val b = derivative(step/2); val c = derivative(step/4)
                    val first = (4*b-a)/3; val second = (4*c-b)/3
                    val tolerance = max(1e-6,1e-3*max(abs(slope),abs(second)))
                    if (!first.isFinite() || !second.isFinite() || abs(first-second) > tolerance || abs(second-slope) > tolerance) bad("CurveSlopeMismatch",p)
                }
            }
            for (join in entry.joins) {
                val p = join.toDouble(); val h = 1e-5
                fun limit(f: (Double) -> Double, sign: Double) = 3*f(p+sign*h)-3*f(p+sign*2*h)+f(p+sign*3*h)
                if (p-3*h >= -.05 && p+3*h <= 1.05) {
                    val left = limit(curve::at, -1.0); val right = limit(curve::at, 1.0)
                    val dl = limit(curve::slope, -1.0); val dr = limit(curve::slope, 1.0)
                    if (!left.isFinite() || !right.isFinite() || abs(left-right) > 1e-4 || abs(curve.at(p)-left) > 1e-4) bad("CurveValueJoin", p)
                    if (!dl.isFinite() || !dr.isFinite() || abs(dl-dr) > max(1e-6, 1e-3*max(abs(dl),abs(dr)))) bad("CurveVelocityJoin", p)
                }
            }
        }
        return result
    }
}
