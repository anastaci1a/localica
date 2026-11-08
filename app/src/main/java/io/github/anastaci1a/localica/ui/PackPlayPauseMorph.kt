package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import io.github.anastaci1a.localica.icons.IconPackImages
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A closed filled outline in unit coordinates: fractions of the icon square, y down. */
internal typealias Contour = List<Offset>

/** Equal-length point lists: a play half and the pause bar it becomes. Every point travels a straight line. */
internal class MorphPiece(val from: Contour, val to: Contour) {
    init { require(from.size == to.size && from.size >= 3) }
    fun at(progress: Float): Contour = from.indices.map { from[it] + (to[it] - from[it]) * progress }
}

/** The precomputed pack morph: the play shape split at the centre of its ink, each half paired with a pause bar. */
internal class PackPlayPauseMorph(val left: MorphPiece, val right: MorphPiece, val cutX: Float, val matching: String)

internal sealed interface PackPlayPausePlan {
    class Morph(val morph: PackPlayPauseMorph) : PackPlayPausePlan
    class Unsupported(val reason: String) : PackPlayPausePlan
}

/** Points per piece; sharp corners are exact anchors, so this only bounds the chord error on rounded parts. */
internal const val PACK_MORPH_POINTS = 128

/** Supported when play is one shape and pause is two separate shapes; anything else names its reason. */
internal fun planPackPlayPause(play: ImageVector, pause: ImageVector, points: Int = PACK_MORPH_POINTS): PackPlayPausePlan = try {
    planPackPlayPause(play.fillContours(), pause.fillContours(), points)
} catch (failure: IllegalArgumentException) {
    PackPlayPausePlan.Unsupported(failure.message ?: "unsupported icon geometry")
}

internal fun planPackPlayPause(play: List<Contour>, pause: List<Contour>, points: Int = PACK_MORPH_POINTS): PackPlayPausePlan = try {
    require(play.size == 1) { "play has ${play.size} shapes, not 1" }
    require(pause.size == 2) { "pause has ${pause.size} shapes, not 2" }
    val triangle = oriented(play.single())
    val bars = pause.map(::oriented).sortedBy { centroid(it).x }
    require(isSimple(triangle)) { "the play shape crosses itself" }
    bars.forEach { require(isSimple(it)) { "a pause shape crosses itself" } }
    require(bars[0].maxOf { it.x } <= bars[1].minOf { it.x } + EPSILON) { "the pause shapes overlap horizontally" }
    val cutX = centroid(triangle).x
    val halves = listOf(clip(triangle, cutX, keepLeft = true), clip(triangle, cutX, keepLeft = false))
    halves.forEach { half ->
        require(half.size >= 3 && half.count { abs(it.x - cutX) <= EPSILON } == 2 && isSimple(half)) {
            "the vertical cut does not split the play shape into two simple pieces"
        }
        require(signedArea(half) >= .05f * signedArea(triangle)) { "a play half is too thin to morph" }
    }
    val pieces = halves.indices.map { correspond(halves[it], bars[it], cutX, points) }
    val matching = if (pieces.all { it.second }) "corner-matched" else "arc-length"
    PackPlayPausePlan.Morph(PackPlayPauseMorph(pieces[0].first, pieces[1].first, cutX, matching))
} catch (failure: IllegalArgumentException) {
    PackPlayPausePlan.Unsupported(failure.message ?: "unsupported icon geometry")
}

/** One plan per published pack; the resolver keeps the previous one while the pack is unchanged. */
internal object PackPlayPauseMorphs {
    private var key: IconPackImages? = null
    private var plan: PackPlayPausePlan? = null

    @Synchronized fun plan(images: IconPackImages, builtin: (AppIcon) -> ImageVector,
        log: (String) -> Unit = { android.util.Log.i("TransportMorph", it) }): PackPlayPausePlan {
        if (key === images) return plan!!
        val result = compute(images, builtin)
        key = images
        plan = result
        log(when (result) {
            is PackPlayPausePlan.Morph -> "Pack play/pause morph ready (${result.morph.matching}, ${result.morph.left.from.size} points per piece)"
            is PackPlayPausePlan.Unsupported -> "Pack play/pause morph unavailable: ${result.reason}; cross-fading instead"
        })
        return result
    }

    private fun compute(images: IconPackImages, builtin: (AppIcon) -> ImageVector): PackPlayPausePlan {
        for (icon in listOf(AppIcon.TransportPlay, AppIcon.TransportPause)) {
            if (icon.id in images.masks) return PackPlayPausePlan.Unsupported("${icon.id} is a raster image")
        }
        val vectors = listOf(AppIcon.TransportPlay, AppIcon.TransportPause).map { icon ->
            images.vectors[icon.id] ?: runCatching { builtin(icon) }.getOrElse {
                return PackPlayPausePlan.Unsupported("the built-in ${icon.id} geometry is unavailable")
            }
        }
        return planPackPlayPause(vectors[0], vectors[1])
    }
}

/** At the ends the static painters draw, so those frames equal the pack icons pixel for pixel. */
internal fun DrawScope.drawPackPlayPause(pause: Float, color: Color, morph: PackPlayPauseMorph,
    playPainter: Painter, pausePainter: Painter) {
    when {
        pause <= 0f -> with(playPainter) { draw(size, colorFilter = ColorFilter.tint(color)) }
        pause >= 1f -> with(pausePainter) { draw(size, colorFilter = ColorFilter.tint(color)) }
        else -> {
            // One path for both pieces: shared winding unions them without an anti-aliased seam.
            val path = Path()
            for (piece in listOf(morph.left, morph.right)) {
                for (i in piece.from.indices) {
                    val p = piece.from[i] + (piece.to[i] - piece.from[i]) * pause
                    if (i == 0) path.moveTo(p.x * size.width, p.y * size.height)
                    else path.lineTo(p.x * size.width, p.y * size.height)
                }
                path.close()
            }
            drawPath(path, color)
        }
    }
}

// ---- Vector geometry -------------------------------------------------------------------------

private const val EPSILON = 1e-5f
private const val CURVE_SEGMENTS = 16
private const val MIN_AREA = 1e-5f
private const val STRAIGHT_TURN = .0087f // half a degree
private const val CORNER_TURN = .61f // 35 degrees
private const val CORNER_GAP = .03f // of the perimeter
private const val CORNER_SPAN = .25f // of the perimeter

private typealias Matrix = FloatArray // a b c d e f: x' = a x + c y + e, y' = b x + d y + f

private fun translation(x: Float, y: Float): Matrix = floatArrayOf(1f, 0f, 0f, 1f, x, y)
private fun scaling(x: Float, y: Float): Matrix = floatArrayOf(x, 0f, 0f, y, 0f, 0f)
private fun rotation(degrees: Float): Matrix {
    val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
    return floatArrayOf(c, s, -s, c, 0f, 0f)
}
private fun Matrix.map(p: Offset) = Offset(this[0] * p.x + this[2] * p.y + this[4], this[1] * p.x + this[3] * p.y + this[5])
/** `this` applied after [inner]. */
private operator fun Matrix.times(inner: Matrix): Matrix = floatArrayOf(
    this[0] * inner[0] + this[2] * inner[1], this[1] * inner[0] + this[3] * inner[1],
    this[0] * inner[2] + this[2] * inner[3], this[1] * inner[2] + this[3] * inner[3],
    this[0] * inner[4] + this[2] * inner[5] + this[4], this[1] * inner[4] + this[3] * inner[5] + this[5])

/** Every filled outline of the vector in unit coordinates; strokes, clips, trims and translucency are rejected. */
internal fun ImageVector.fillContours(): List<Contour> {
    val result = mutableListOf<Contour>()
    fun walk(group: VectorGroup, parent: Matrix) {
        require(group.clipPathData.isEmpty()) { "the icon uses a clip path" }
        val matrix = parent * translation(group.translationX + group.pivotX, group.translationY + group.pivotY) *
            rotation(group.rotation) * scaling(group.scaleX, group.scaleY) * translation(-group.pivotX, -group.pivotY)
        for (node in group) when (node) {
            is VectorGroup -> walk(node, matrix)
            is VectorPath -> {
                require(node.stroke == null || node.strokeLineWidth <= 0f || node.strokeAlpha <= 0f) { "the icon has a stroked path" }
                require(node.fill != null) { "the icon has an unfilled path" }
                require(node.fillAlpha >= 1f) { "the icon has a translucent fill" }
                require(node.trimPathStart == 0f && node.trimPathEnd == 1f) { "the icon has a trimmed path" }
                result += flatten(node.pathData, matrix)
            }
        }
    }
    walk(root, scaling(1f / viewportWidth, 1f / viewportHeight))
    return result
}

private fun flatten(nodes: List<PathNode>, matrix: Matrix): List<Contour> {
    val contours = mutableListOf<Contour>()
    var current = mutableListOf<Offset>()
    var point = Offset.Zero
    var start = Offset.Zero
    var lastCubicControl: Offset? = null
    var lastQuadControl: Offset? = null
    fun emit() {
        val mapped = dedupe(current.map(matrix::map))
        if (mapped.size >= 3 && abs(signedArea(mapped)) >= MIN_AREA) contours += mapped
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
            is PathNode.Close -> { emit(); point = start }
            is PathNode.MoveTo -> { emit(); point = Offset(node.x, node.y); start = point; current += point }
            is PathNode.RelativeMoveTo -> { emit(); point += Offset(node.dx, node.dy); start = point; current += point }
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
    emit()
    return contours
}

internal fun dedupe(points: List<Offset>): Contour {
    val result = mutableListOf<Offset>()
    for (p in points) if (result.isEmpty() || (p - result.last()).getDistance() > 1e-6f) result += p
    while (result.size > 1 && (result.first() - result.last()).getDistance() <= 1e-6f) result.removeAt(result.lastIndex)
    return result
}

// ---- Polygon measures ------------------------------------------------------------------------

internal fun signedArea(c: Contour): Float {
    var sum = 0.0
    for (i in c.indices) { val a = c[i]; val b = c[(i + 1) % c.size]; sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
    return (sum / 2).toFloat()
}

internal fun centroid(c: Contour): Offset {
    var x = 0.0; var y = 0.0; var area = 0.0
    for (i in c.indices) {
        val a = c[i]; val b = c[(i + 1) % c.size]
        val cross = a.x.toDouble() * b.y - b.x.toDouble() * a.y
        area += cross; x += (a.x + b.x) * cross; y += (a.y + b.y) * cross
    }
    return Offset((x / (3 * area)).toFloat(), (y / (3 * area)).toFloat())
}

/** Clockwise on screen (y down) has positive area; every piece shares it so no winding ever flips. */
private fun oriented(c: Contour): Contour = if (signedArea(c) < 0f) c.reversed() else c

private fun cross(o: Offset, a: Offset, b: Offset): Double =
    (a.x.toDouble() - o.x) * (b.y.toDouble() - o.y) - (a.y.toDouble() - o.y) * (b.x.toDouble() - o.x)

private fun onSegment(a: Offset, b: Offset, p: Offset) =
    p.x >= min(a.x, b.x) - EPSILON && p.x <= max(a.x, b.x) + EPSILON && p.y >= min(a.y, b.y) - EPSILON && p.y <= max(a.y, b.y) + EPSILON

/** Touching counts as intersecting. */
internal fun segmentsIntersect(p1: Offset, p2: Offset, q1: Offset, q2: Offset): Boolean {
    val d1 = cross(q1, q2, p1); val d2 = cross(q1, q2, p2); val d3 = cross(p1, p2, q1); val d4 = cross(p1, p2, q2)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
    val tiny = 1e-9
    if (abs(d1) <= tiny && onSegment(q1, q2, p1)) return true
    if (abs(d2) <= tiny && onSegment(q1, q2, p2)) return true
    if (abs(d3) <= tiny && onSegment(p1, p2, q1)) return true
    if (abs(d4) <= tiny && onSegment(p1, p2, q2)) return true
    return false
}

/** No two non-adjacent edges meet. */
internal fun isSimple(c: Contour): Boolean {
    val n = c.size
    if (n < 3) return false
    for (i in 0 until n) for (j in i + 2 until n) {
        if (i == 0 && j == n - 1) continue
        if (segmentsIntersect(c[i], c[(i + 1) % n], c[j], c[(j + 1) % n])) return false
    }
    return true
}

internal fun contains(c: Contour, p: Offset): Boolean {
    var inside = false
    var j = c.size - 1
    for (i in c.indices) {
        val a = c[i]; val b = c[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** Two closed outlines share no edge or interior. */
internal fun disjoint(a: Contour, b: Contour): Boolean {
    for (i in a.indices) for (j in b.indices)
        if (segmentsIntersect(a[i], a[(i + 1) % a.size], b[j], b[(j + 1) % b.size])) return false
    return !contains(a, b[0]) && !contains(b, a[0])
}

private fun clip(c: Contour, cutX: Float, keepLeft: Boolean): Contour {
    fun inside(p: Offset) = if (keepLeft) p.x <= cutX else p.x >= cutX
    val out = mutableListOf<Offset>()
    for (i in c.indices) {
        val a = c[i]; val b = c[(i + 1) % c.size]
        val insideA = inside(a); val insideB = inside(b)
        if (insideA) out += a
        if (insideA != insideB) out += Offset(cutX, a.y + (b.y - a.y) * (cutX - a.x) / (b.x - a.x))
    }
    return dedupe(out)
}

// ---- Correspondence --------------------------------------------------------------------------

private fun perimeter(c: Contour): Float {
    var sum = 0f
    for (i in c.indices) sum += (c[(i + 1) % c.size] - c[i]).getDistance()
    return sum
}

private fun turning(c: Contour, i: Int): Float {
    val n = c.size
    val u = c[i] - c[(i + n - 1) % n]; val v = c[(i + 1) % n] - c[i]
    return atan2(u.x * v.y - u.y * v.x, u.x * v.x + u.y * v.y)
}

/** Vertex indices of the corners, in path order; a rounded corner counts once at its middle.
 * Turning vertices closer than [CORNER_GAP] cluster; the walk starts after the longest edge so no cluster wraps. */
internal fun corners(c: Contour): List<Int> {
    val n = c.size
    val total = perimeter(c)
    val turns = FloatArray(n) { turning(c, it) }
    val first = (0 until n).maxBy { (c[it] - c[(it + n - 1) % n]).getDistance() }
    val result = mutableListOf<Int>()
    val cluster = mutableListOf<Int>()
    var clusterSpan = 0f
    var sinceLast = 0f
    fun close() {
        if (cluster.isEmpty()) return
        val turn = cluster.sumOf { turns[it].toDouble() }.toFloat()
        if (abs(turn) >= CORNER_TURN && clusterSpan <= CORNER_SPAN * total) {
            var cumulative = 0f
            result += cluster.first { cumulative += abs(turns[it]); cumulative >= abs(turn) / 2f - 1e-6f }
        }
        cluster.clear()
        clusterSpan = 0f
    }
    for (step in 0 until n) {
        val i = (first + step) % n
        sinceLast += (c[i] - c[(i + n - 1) % n]).getDistance()
        if (abs(turns[i]) < STRAIGHT_TURN) continue
        if (cluster.isNotEmpty() && (sinceLast > CORNER_GAP * total || turns[i].sign() != turns[cluster.last()].sign())) close()
        if (cluster.isNotEmpty()) clusterSpan += sinceLast
        cluster += i
        sinceLast = 0f
    }
    close()
    return result.sorted()
}

private fun length(path: List<Offset>): Float {
    var sum = 0f
    for (i in 1 until path.size) sum += (path[i] - path[i - 1]).getDistance()
    return sum
}

private fun Float.sign() = if (this < 0f) -1 else 1

/** The start of each piece is the corner, or failing that the vertex, nearest its own top-left bound. */
private fun startIndex(c: Contour, candidates: List<Int>): Int {
    val topLeft = Offset(c.minOf { it.x }, c.minOf { it.y })
    val pool = candidates.ifEmpty { c.indices.toList() }
    return pool.minBy { (c[it] - topLeft).getDistance() }
}

private fun rotateTo(anchors: List<Int>, start: Int): List<Int> {
    val at = anchors.indexOf(start)
    return anchors.drop(at) + anchors.take(at)
}

/** Points along the open polyline [path] at the given non-decreasing arc positions. */
private fun pointsAt(path: List<Offset>, positions: FloatArray): List<Offset> {
    val lengths = FloatArray(path.size - 1) { (path[it + 1] - path[it]).getDistance() }
    if (lengths.sum() <= 0f) return List(positions.size) { path.first() }
    val result = ArrayList<Offset>(positions.size)
    var segment = 0
    var consumed = 0f
    for (s in positions) {
        while (segment < lengths.size - 1 && consumed + lengths[segment] < s) { consumed += lengths[segment]; segment++ }
        val t = if (lengths[segment] > 0f) ((s - consumed) / lengths[segment]).coerceIn(0f, 1f) else 0f
        result += path[segment] + (path[segment + 1] - path[segment]) * t
    }
    return result
}

/** Equal arc-length steps, start inclusive and end exclusive. */
private fun sampleAlong(path: List<Offset>, count: Int): List<Offset> =
    pointsAt(path, FloatArray(count) { length(path) * it / count })

/** Vertices from [from] forward to [to]; the same index is a zero-length run. */
private fun run(c: Contour, from: Int, to: Int): List<Offset> {
    val result = mutableListOf(c[from])
    var i = from
    while (i != to) { i = (i + 1) % c.size; result += c[i] }
    return result
}

private fun resampleByAnchors(c: Contour, anchors: List<Int>, counts: List<Int>): Contour =
    anchors.indices.flatMap { k -> sampleAlong(run(c, anchors[k], anchors[(k + 1) % anchors.size]), counts[k]) }

/** Uniform arc length around the loop; the sample nearest each sharp vertex snaps onto it so cut corners stay exact. */
private fun resampleUniform(c: Contour, start: Int, count: Int): Contour {
    val loop = (0..c.size).map { c[(start + it) % c.size] }
    val total = length(loop)
    val positions = FloatArray(count) { total * it / count }
    val snapped = BooleanArray(count)
    var arc = 0f
    for (k in 0 until c.size) {
        if (k > 0) arc += (loop[k] - loop[k - 1]).getDistance()
        if (abs(turning(c, (start + k) % c.size)) < CORNER_TURN) continue
        val j = ((arc / total) * count).roundToInt()
        if (j < count && !snapped[j]) { positions[j] = arc; snapped[j] = true }
    }
    return pointsAt(loop, positions)
}

/** Corner-matched when both outlines expose the same corners (a lone apex doubles onto a bar's two far corners),
 * otherwise uniform arc length from geometrically aligned starts. */
private fun correspond(piece: Contour, bar: Contour, cutX: Float, points: Int): Pair<MorphPiece, Boolean> {
    val pieceCorners = corners(piece)
    val barCorners = corners(bar)
    val pieceStart = startIndex(piece, pieceCorners)
    val barStart = startIndex(bar, barCorners)
    if (pieceCorners.size >= 3 && barCorners.size >= 3) {
        var pieceAnchors = rotateTo(pieceCorners, pieceStart)
        val barAnchors = rotateTo(barCorners, barStart)
        if (pieceAnchors.size == barAnchors.size - 1) {
            val apex = pieceAnchors.indices.maxBy { abs(piece[pieceAnchors[it]].x - cutX) }
            pieceAnchors = pieceAnchors.take(apex + 1) + pieceAnchors.drop(apex)
        }
        if (pieceAnchors.size == barAnchors.size) {
            val pieceTotal = perimeter(piece); val barTotal = perimeter(bar)
            val weights = pieceAnchors.indices.map { k ->
                val next = (k + 1) % pieceAnchors.size
                max(length(run(piece, pieceAnchors[k], pieceAnchors[next])) / pieceTotal,
                    length(run(bar, barAnchors[k], barAnchors[next])) / barTotal)
            }
            val weightSum = weights.sum()
            val counts = weights.map { max(1, (points * it / weightSum).roundToInt()) }
            return MorphPiece(resampleByAnchors(piece, pieceAnchors, counts), resampleByAnchors(bar, barAnchors, counts)) to true
        }
    }
    return MorphPiece(resampleUniform(piece, pieceStart, points), resampleUniform(bar, barStart, points)) to false
}
