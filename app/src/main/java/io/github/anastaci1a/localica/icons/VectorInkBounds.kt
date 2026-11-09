package io.github.anastaci1a.localica.icons

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.*

/** Analytic vertical support of fills and stroked outlines, independent of the centroid sampling grid. */
internal fun ImageVector.geometricInkBounds(): Pair<Float, Float>? {
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    forEachPath(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)) { path, matrix ->
        fun Brush?.visible() = this != null && (this !is SolidColor || value.alpha > 0f)
        val fill = path.fill.visible() && path.fillAlpha > 0f
        val stroke = path.stroke.visible() && path.strokeAlpha > 0f && path.strokeLineWidth > 0f
        if (fill || stroke) {
            val bounds = VerticalSupport(matrix[1].toDouble(), matrix[3].toDouble(), matrix[5].toDouble())
            for (part in boundSubpaths(path.pathData)) {
                if (fill && part.segments.any { !it.isPoint() }) part.segments.forEach { segment ->
                    segment.extrema(bounds.x, bounds.y).forEach { bounds.add(segment.point(it)) }
                }
                if (stroke) bounds.stroke(part, path.strokeLineWidth / 2.0, path.strokeLineCap,
                    path.strokeLineJoin, path.strokeLineMiter.toDouble())
            }
            low = min(low, bounds.low); high = max(high, bounds.high)
        }
    }
    if (!low.isFinite() || !high.isFinite()) return null
    // Outward rounding keeps the float clamp inside the analytic double bounds.
    fun down(value: Double): Float = value.toFloat().let { if (it.toDouble() > value) Math.nextDown(it) else it }
    fun up(value: Double): Float = value.toFloat().let { if (it.toDouble() < value) Math.nextUp(it) else it }
    return down(low) to up(high)
}

private data class Point(val x: Double, val y: Double) {
    operator fun plus(p: Point) = Point(x + p.x, y + p.y)
    operator fun minus(p: Point) = Point(x - p.x, y - p.y)
    operator fun times(s: Double) = Point(x * s, y * s)
    fun dot(p: Point) = x * p.x + y * p.y
    fun cross(p: Point) = x * p.y - y * p.x
    fun unit(): Point { val size = hypot(x, y); return if (size == 0.0) Point(0.0, 0.0) else this * (1.0 / size) }
    fun normal() = Point(-y, x)
}

private sealed interface BoundSegment {
    fun isPoint(): Boolean
    fun point(t: Double): Point
    fun tangent(t: Double): Point
    fun extrema(x: Double, y: Double): List<Double>
    fun strokeExtrema(x: Double, y: Double, radius: Double): List<Double>
}

private class Bezier(val x: DoubleArray, val y: DoubleArray) : BoundSegment {
    override fun isPoint() = x.drop(1).all { it == 0.0 } && y.drop(1).all { it == 0.0 }
    override fun point(t: Double) = Point(x.at(t), y.at(t))
    override fun tangent(t: Double): Point {
        var dx = x.derivative(); var dy = y.derivative()
        var order = 1
        var v = Point(dx.at(t), dy.at(t))
        // Degenerate endpoint controls retain the first nonzero derivative's tangent.
        while (v.dot(v) < 1e-24 && (dx.size > 1 || dy.size > 1)) {
            dx = dx.derivative(); dy = dy.derivative()
            order++
            v = Point(dx.at(t), dy.at(t)) * (if (t == 1.0 && order % 2 == 0) -1.0 else 1.0)
        }
        return v.unit()
    }
    override fun extrema(x: Double, y: Double): List<Double> =
        listOf(0.0, 1.0) + this.x.scale(x).plus(this.y.scale(y)).derivative().roots()
    override fun strokeExtrema(x: Double, y: Double, radius: Double): List<Double> {
        val dx = this.x.derivative(); val dy = this.y.derivative()
        val speed2 = dx.times(dx).plus(dy.times(dy))
        val cross = dx.times(dy.derivative()).minus(dy.times(dx.derivative()))
        // Offset-curve extrema: projected tangent = 0, or signed curvature = +/- 1/r.
        val cusps = cross.times(cross).scale(radius * radius).minus(speed2.times(speed2).times(speed2)).roots()
        return extrema(x, y) + cusps
    }
}

private class EllipseArc(val centre: Point, val rx: Double, val ry: Double, val phi: Double,
    val start: Double, val sweep: Double) : BoundSegment {
    override fun isPoint() = false
    private fun local(angle: Double) = Point(rx * cos(angle) * cos(phi) - ry * sin(angle) * sin(phi),
        rx * cos(angle) * sin(phi) + ry * sin(angle) * cos(phi))
    override fun point(t: Double) = centre + local(start + sweep * t)
    override fun tangent(t: Double): Point {
        val a = start + sweep * t
        return Point(-rx * sin(a) * cos(phi) - ry * cos(a) * sin(phi),
            -rx * sin(a) * sin(phi) + ry * cos(a) * cos(phi)).times(sweep).unit()
    }
    private fun parameters(angles: List<Double>): List<Double> = angles.flatMap { a ->
        (-2..2).map { (a + it * 2.0 * PI - start) / sweep }.filter { it > 0.0 && it < 1.0 }
    }
    override fun extrema(x: Double, y: Double): List<Double> {
        val a = rx * (x * cos(phi) + y * sin(phi))
        val b = ry * (-x * sin(phi) + y * cos(phi))
        val angle = atan2(b, a)
        return listOf(0.0, 1.0) + parameters(listOf(angle, angle + PI))
    }
    override fun strokeExtrema(x: Double, y: Double, radius: Double): List<Double> {
        val denominator = rx * rx - ry * ry
        if (abs(denominator) < 1e-20) return extrema(x, y)
        val sin2 = ((radius * rx * ry).pow(2.0 / 3.0) - ry * ry) / denominator
        if (sin2 !in 0.0..1.0) return extrema(x, y)
        val angle = asin(sqrt(sin2))
        return extrema(x, y) + parameters(listOf(angle, PI - angle, PI + angle, -angle))
    }
}

private class BoundSubpath(val segments: List<BoundSegment>, val closed: Boolean)

private class VerticalSupport(val x: Double, val y: Double, val translation: Double) {
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    fun add(p: Point) { val value = x * p.x + y * p.y + translation; low = min(low, value); high = max(high, value) }
    private fun circle(p: Point, radius: Double, includes: (Point) -> Boolean) {
        val direction = Point(x, y).unit()
        for (v in listOf(direction, direction * -1.0)) if (includes(v)) add(p + v * radius)
    }
    fun stroke(part: BoundSubpath, radius: Double, cap: StrokeCap, join: StrokeJoin, miter: Double) {
        val segments = part.segments.filterNot { it.isPoint() }
        if (segments.isEmpty()) {
            if (part.closed || part.segments.isEmpty()) return
            val p = part.segments.first().point(0.0)
            if (cap == StrokeCap.Round) circle(p, radius) { true }
            if (cap == StrokeCap.Square) for (dx in listOf(-radius, radius)) for (dy in listOf(-radius, radius))
                add(p + Point(dx, dy))
            return
        }
        for (segment in segments) for (t in segment.strokeExtrema(x, y, radius)) {
            val p = segment.point(t); val n = segment.tangent(t).normal()
            add(p + n * radius); add(p - n * radius)
        }
        fun connect(a: BoundSegment, b: BoundSegment) {
            val p = a.point(1.0); val incoming = a.tangent(1.0); val outgoing = b.tangent(0.0)
            val turn = atan2(incoming.cross(outgoing), incoming.dot(outgoing))
            if (abs(turn) < 1e-12) return
            val side = if (turn > 0.0) -1.0 else 1.0
            val n1 = incoming.normal() * side; val n2 = outgoing.normal() * side
            add(p + n1 * radius); add(p + n2 * radius)
            when (join) {
                StrokeJoin.Round -> circle(p, radius) { v ->
                    val angle = atan2(n1.cross(v), n1.dot(v))
                    if (turn > 0.0) angle >= -1e-12 && angle <= turn + 1e-12 else angle <= 1e-12 && angle >= turn - 1e-12
                }
                StrokeJoin.Miter -> {
                    val denominator = 1.0 + incoming.dot(outgoing)
                    if (denominator > 1e-15) {
                        val tip = (n1 + n2) * (radius / denominator)
                        if (hypot(tip.x, tip.y) <= radius * miter + 1e-12) add(p + tip)
                    }
                }
            }
        }
        for (i in 1 until segments.size) connect(segments[i - 1], segments[i])
        if (part.closed) connect(segments.last(), segments.first()) else {
            for ((segment, t, sign) in listOf(Triple(segments.first(), 0.0, -1.0), Triple(segments.last(), 1.0, 1.0))) {
                val p = segment.point(t); val tangent = segment.tangent(t); val normal = tangent.normal()
                when (cap) {
                    StrokeCap.Round -> circle(p, radius) { it.dot(tangent) * sign >= -1e-12 }
                    StrokeCap.Square -> { add(p + (tangent * sign + normal) * radius); add(p + (tangent * sign - normal) * radius) }
                }
            }
        }
    }
}

private fun DoubleArray.at(t: Double): Double {
    var value = 0.0
    for (i in lastIndex downTo 0) value = value * t + this[i]
    return value
}
private fun DoubleArray.derivative() = if (size < 2) doubleArrayOf(0.0) else DoubleArray(size - 1) { this[it + 1] * (it + 1) }
private fun DoubleArray.scale(s: Double) = DoubleArray(size) { this[it] * s }
private fun DoubleArray.plus(b: DoubleArray) = DoubleArray(max(size, b.size)) { getOrElse(it) { 0.0 } + b.getOrElse(it) { 0.0 } }
private fun DoubleArray.minus(b: DoubleArray) = plus(b.scale(-1.0))
private fun DoubleArray.times(b: DoubleArray): DoubleArray = DoubleArray(size + b.size - 1).also { result ->
    for (i in indices) for (j in b.indices) result[i + j] += this[i] * b[j]
}

/** Isolate polynomial roots by derivative roots, then bisect monotonic intervals; no spatial sampling. */
private fun DoubleArray.roots(): List<Double> {
    val magnitude = maxOfOrNull { abs(it) } ?: 0.0
    if (magnitude == 0.0) return emptyList()
    val coefficients = scale(1.0 / magnitude).toMutableList()
    while (coefficients.size > 1 && abs(coefficients.last()) < 1e-14) coefficients.removeAt(coefficients.lastIndex)
    if (coefficients.size <= 1) return emptyList()
    val p = coefficients.toDoubleArray()
    if (p.size == 2) return listOf(-p[0] / p[1]).filter { it > 0.0 && it < 1.0 }
    val stops = listOf(0.0) + p.derivative().roots() + listOf(1.0)
    val found = mutableListOf<Double>()
    for (t in stops) if (t > 0.0 && t < 1.0 && abs(p.at(t)) < 1e-12) found += t
    for ((a, b) in stops.zipWithNext()) {
        if (p.at(a) * p.at(b) >= 0.0) continue
        var lo = a; var hi = b
        repeat(60) { val mid = (lo + hi) / 2.0; if (p.at(lo) * p.at(mid) <= 0.0) hi = mid else lo = mid }
        found += (lo + hi) / 2.0
    }
    return found.distinct().sorted()
}

private fun boundSubpaths(nodes: List<PathNode>): List<BoundSubpath> {
    val result = mutableListOf<BoundSubpath>()
    var segments = mutableListOf<BoundSegment>()
    var point = Point(0.0, 0.0); var start = point
    var cubic: Point? = null; var quadratic: Point? = null
    fun emit(closed: Boolean) { if (segments.isNotEmpty()) result += BoundSubpath(segments, closed); segments = mutableListOf() }
    fun line(p: Point) { segments += Bezier(doubleArrayOf(point.x, p.x - point.x), doubleArrayOf(point.y, p.y - point.y)); point = p }
    fun curve(a: Point, b: Point, p: Point) {
        fun coefficients(v: Double, a: Double, b: Double, p: Double) = doubleArrayOf(v, 3 * (a - v), 3 * (v - 2 * a + b), p - v + 3 * (a - b))
        segments += Bezier(coefficients(point.x, a.x, b.x, p.x), coefficients(point.y, a.y, b.y, p.y)); point = p; cubic = b
    }
    fun quad(a: Point, p: Point) {
        segments += Bezier(doubleArrayOf(point.x, 2 * (a.x - point.x), point.x - 2 * a.x + p.x),
            doubleArrayOf(point.y, 2 * (a.y - point.y), point.y - 2 * a.y + p.y)); point = p; quadratic = a
    }
    fun arc(rx0: Float, ry0: Float, degrees: Float, large: Boolean, positive: Boolean, p: Point) {
        if (point == p) return
        if (rx0 == 0f || ry0 == 0f) { line(p); return }
        val phi = Math.toRadians(degrees.toDouble()); val c = cos(phi); val s = sin(phi)
        val dx = (point.x - p.x) / 2; val dy = (point.y - p.y) / 2
        val u = c * dx + s * dy; val v = -s * dx + c * dy
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        val correction = u * u / (rx * rx) + v * v / (ry * ry)
        if (correction > 1) { val scale = sqrt(correction); rx *= scale; ry *= scale }
        val factor = (if (large != positive) 1 else -1) * sqrt(max(0.0,
            (rx * rx * ry * ry - rx * rx * v * v - ry * ry * u * u) / (rx * rx * v * v + ry * ry * u * u)))
        val cx = factor * rx * v / ry; val cy = -factor * ry * u / rx
        val centre = Point(c * cx - s * cy + (point.x + p.x) / 2, s * cx + c * cy + (point.y + p.y) / 2)
        val from = Point((u - cx) / rx, (v - cy) / ry); val to = Point((-u - cx) / rx, (-v - cy) / ry)
        val begin = atan2(from.y, from.x)
        var sweep = atan2(from.cross(to), from.dot(to))
        if (!positive && sweep > 0) sweep -= 2 * PI
        if (positive && sweep < 0) sweep += 2 * PI
        segments += EllipseArc(centre, rx, ry, phi, begin, sweep); point = p
    }
    for (node in nodes) {
        val reflectedCubic = cubic?.let { point * 2.0 - it } ?: point
        val reflectedQuad = quadratic?.let { point * 2.0 - it } ?: point
        cubic = null; quadratic = null
        fun p(x: Float, y: Float) = Point(x.toDouble(), y.toDouble())
        when (node) {
            is PathNode.Close -> { if (point != start) line(start); emit(true); point = start }
            is PathNode.MoveTo -> { emit(false); point = p(node.x, node.y); start = point }
            is PathNode.RelativeMoveTo -> { emit(false); point += p(node.dx, node.dy); start = point }
            is PathNode.LineTo -> line(p(node.x, node.y))
            is PathNode.RelativeLineTo -> line(point + p(node.dx, node.dy))
            is PathNode.HorizontalTo -> line(Point(node.x.toDouble(), point.y))
            is PathNode.RelativeHorizontalTo -> line(point + Point(node.dx.toDouble(), 0.0))
            is PathNode.VerticalTo -> line(Point(point.x, node.y.toDouble()))
            is PathNode.RelativeVerticalTo -> line(point + Point(0.0, node.dy.toDouble()))
            is PathNode.CurveTo -> curve(p(node.x1, node.y1), p(node.x2, node.y2), p(node.x3, node.y3))
            is PathNode.RelativeCurveTo -> curve(point + p(node.dx1, node.dy1), point + p(node.dx2, node.dy2), point + p(node.dx3, node.dy3))
            is PathNode.ReflectiveCurveTo -> curve(reflectedCubic, p(node.x1, node.y1), p(node.x2, node.y2))
            is PathNode.RelativeReflectiveCurveTo -> curve(reflectedCubic, point + p(node.dx1, node.dy1), point + p(node.dx2, node.dy2))
            is PathNode.QuadTo -> quad(p(node.x1, node.y1), p(node.x2, node.y2))
            is PathNode.RelativeQuadTo -> quad(point + p(node.dx1, node.dy1), point + p(node.dx2, node.dy2))
            is PathNode.ReflectiveQuadTo -> quad(reflectedQuad, p(node.x, node.y))
            is PathNode.RelativeReflectiveQuadTo -> quad(reflectedQuad, point + p(node.dx, node.dy))
            is PathNode.ArcTo -> arc(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, p(node.arcStartX, node.arcStartY))
            is PathNode.RelativeArcTo -> arc(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, point + p(node.arcStartDx, node.arcStartDy))
        }
    }
    emit(false)
    return result
}
