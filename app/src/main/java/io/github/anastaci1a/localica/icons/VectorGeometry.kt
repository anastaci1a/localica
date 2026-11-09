package io.github.anastaci1a.localica.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure vector-path geometry shared by pack processing (optical centring, play/pause morph); never rasterizes for drawing. */
internal typealias Matrix = FloatArray // a b c d e f: x' = a x + c y + e, y' = b x + d y + f

internal fun translation(x: Float, y: Float): Matrix = floatArrayOf(1f, 0f, 0f, 1f, x, y)
internal fun scaling(x: Float, y: Float): Matrix = floatArrayOf(x, 0f, 0f, y, 0f, 0f)
internal fun rotation(degrees: Float): Matrix {
    val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
    return floatArrayOf(c, s, -s, c, 0f, 0f)
}
internal fun Matrix.map(p: Offset) = Offset(this[0] * p.x + this[2] * p.y + this[4], this[1] * p.x + this[3] * p.y + this[5])
/** `this` applied after [inner]. */
internal operator fun Matrix.times(inner: Matrix): Matrix = floatArrayOf(
    this[0] * inner[0] + this[2] * inner[1], this[1] * inner[0] + this[3] * inner[1],
    this[0] * inner[2] + this[2] * inner[3], this[1] * inner[2] + this[3] * inner[3],
    this[0] * inner[4] + this[2] * inner[5] + this[4], this[1] * inner[4] + this[3] * inner[5] + this[5])

/** Visits every path with its accumulated group transform, starting from [initial]. */
internal fun ImageVector.forEachPath(initial: Matrix, onGroup: (VectorGroup) -> Unit = {}, action: (VectorPath, Matrix) -> Unit) {
    fun walk(group: VectorGroup, parent: Matrix) {
        onGroup(group)
        val matrix = parent * translation(group.translationX + group.pivotX, group.translationY + group.pivotY) *
            rotation(group.rotation) * scaling(group.scaleX, group.scaleY) * translation(-group.pivotX, -group.pivotY)
        for (node in group) when (node) {
            is VectorGroup -> walk(node, matrix)
            is VectorPath -> action(node, matrix)
        }
    }
    walk(root, initial)
}

/** One flattened subpath in path coordinates; [closed] when it ended with a close command. */
internal class Subpath(val points: List<Offset>, val closed: Boolean)

private const val CURVE_SEGMENTS = 16

/** Every subpath as a polyline: curves use fixed chords and arcs at most 11.25 degrees per chord. */
internal fun flattenSubpaths(nodes: List<PathNode>): List<Subpath> {
    val subpaths = mutableListOf<Subpath>()
    var current = mutableListOf<Offset>()
    var point = Offset.Zero
    var start = Offset.Zero
    var lastCubicControl: Offset? = null
    var lastQuadControl: Offset? = null
    fun emit(closed: Boolean) {
        if (current.isNotEmpty()) subpaths += Subpath(current, closed)
        current = mutableListOf()
    }
    fun begin() { if (current.isEmpty()) current += point }
    fun lineTo(p: Offset) { begin(); current += p; point = p }
    fun cubicTo(c1: Offset, c2: Offset, end: Offset) {
        begin()
        val p0 = point
        for (i in 1..CURVE_SEGMENTS) {
            val t = i.toFloat() / CURVE_SEGMENTS; val u = 1f - t
            current += p0 * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + end * (t * t * t)
        }
        point = end
        lastCubicControl = c2
    }
    fun quadTo(c: Offset, end: Offset) {
        begin()
        val p0 = point
        for (i in 1..CURVE_SEGMENTS) {
            val t = i.toFloat() / CURVE_SEGMENTS; val u = 1f - t
            current += p0 * (u * u) + c * (2f * u * t) + end * (t * t)
        }
        point = end
        lastQuadControl = c
    }
    fun arcTo(rx0: Float, ry0: Float, degrees: Float, large: Boolean, sweep: Boolean, end: Offset) {
        begin()
        val p0 = point
        if (p0 == end) return
        if (rx0 == 0f || ry0 == 0f) { lineTo(end); return }
        val phi = Math.toRadians(degrees.toDouble()); val cosPhi = cos(phi); val sinPhi = sin(phi)
        val dx = (p0.x - end.x) / 2.0; val dy = (p0.y - end.y) / 2.0
        val x1 = cosPhi * dx + sinPhi * dy; val y1 = -sinPhi * dx + cosPhi * dy
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        val lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val sign = if (large != sweep) 1.0 else -1.0
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val coefficient = sign * sqrt(max(0.0, numerator / (rx * rx * y1 * y1 + ry * ry * x1 * x1)))
        val cx1 = coefficient * rx * y1 / ry; val cy1 = -coefficient * ry * x1 / rx
        val cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + end.x) / 2.0
        val cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + end.y) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy; val length = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            val a = acos((dot / length).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta = angle(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI
        val segments = max(2, ceil(abs(delta) / (Math.PI / 16)).toInt())
        for (i in 1 until segments) {
            val t = theta + delta * i / segments
            current += Offset((cx + rx * cos(t) * cosPhi - ry * sin(t) * sinPhi).toFloat(),
                (cy + rx * cos(t) * sinPhi + ry * sin(t) * cosPhi).toFloat())
        }
        current += end
        point = end
    }
    for (node in nodes) {
        val cubicReflection = lastCubicControl?.let { point * 2f - it } ?: point
        val quadReflection = lastQuadControl?.let { point * 2f - it } ?: point
        lastCubicControl = null
        lastQuadControl = null
        when (node) {
            is PathNode.Close -> { emit(closed = true); point = start }
            is PathNode.MoveTo -> { emit(closed = false); point = Offset(node.x, node.y); start = point; current += point }
            is PathNode.RelativeMoveTo -> { emit(closed = false); point += Offset(node.dx, node.dy); start = point; current += point }
            is PathNode.LineTo -> lineTo(Offset(node.x, node.y))
            is PathNode.RelativeLineTo -> lineTo(point + Offset(node.dx, node.dy))
            is PathNode.HorizontalTo -> lineTo(Offset(node.x, point.y))
            is PathNode.RelativeHorizontalTo -> lineTo(Offset(point.x + node.dx, point.y))
            is PathNode.VerticalTo -> lineTo(Offset(point.x, node.y))
            is PathNode.RelativeVerticalTo -> lineTo(Offset(point.x, point.y + node.dy))
            is PathNode.CurveTo -> cubicTo(Offset(node.x1, node.y1), Offset(node.x2, node.y2), Offset(node.x3, node.y3))
            is PathNode.RelativeCurveTo -> cubicTo(point + Offset(node.dx1, node.dy1), point + Offset(node.dx2, node.dy2), point + Offset(node.dx3, node.dy3))
            is PathNode.ReflectiveCurveTo -> cubicTo(cubicReflection, Offset(node.x1, node.y1), Offset(node.x2, node.y2))
            is PathNode.RelativeReflectiveCurveTo -> cubicTo(cubicReflection, point + Offset(node.dx1, node.dy1), point + Offset(node.dx2, node.dy2))
            is PathNode.QuadTo -> quadTo(Offset(node.x1, node.y1), Offset(node.x2, node.y2))
            is PathNode.RelativeQuadTo -> quadTo(point + Offset(node.dx1, node.dy1), point + Offset(node.dx2, node.dy2))
            is PathNode.ReflectiveQuadTo -> quadTo(quadReflection, Offset(node.x, node.y))
            is PathNode.RelativeReflectiveQuadTo -> quadTo(quadReflection, point + Offset(node.dx, node.dy))
            is PathNode.ArcTo -> arcTo(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, Offset(node.arcStartX, node.arcStartY))
            is PathNode.RelativeArcTo -> arcTo(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, point + Offset(node.arcStartDx, node.arcStartDy))
        }
    }
    emit(closed = false)
    return subpaths
}
