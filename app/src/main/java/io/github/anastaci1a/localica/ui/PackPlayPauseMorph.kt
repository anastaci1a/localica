package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.anastaci1a.localica.icons.IconPackImages
import io.github.anastaci1a.localica.icons.flattenSubpaths
import io.github.anastaci1a.localica.icons.forEachPath
import io.github.anastaci1a.localica.icons.map
import io.github.anastaci1a.localica.icons.scaling
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A closed filled outline in unit coordinates: fractions of the icon square, y down. */
internal typealias Contour = List<Offset>

/** Equal-length point lists: a play half and the pause bar it becomes. Every point travels a straight line. */
internal class MorphPiece(val from: Contour, val to: Contour) {
    init { require(from.size == to.size && from.size >= 3) }
    fun at(progress: Float): Contour = from.indices.map { from[it] + (to[it] - from[it]) * progress }
}

/** The precomputed pack morph: the play shape split in the pause bars' width proportion, each half paired with its bar. */
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
    val cutX = proportionalCut(triangle, bars)
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
private const val MIN_AREA = 1e-5f
private const val STRAIGHT_TURN = .0087f // half a degree
private const val CORNER_TURN = .61f // 35 degrees
private const val CORNER_GAP = .03f // of the perimeter
private const val CORNER_SPAN = .25f // of the perimeter

/** Every filled outline of the vector in unit coordinates; strokes, clips, trims and translucency are rejected. */
internal fun ImageVector.fillContours(): List<Contour> {
    val result = mutableListOf<Contour>()
    forEachPath(scaling(1f / viewportWidth, 1f / viewportHeight),
        onGroup = { group -> require(group.clipPathData.isEmpty()) { "the icon uses a clip path" } }) { node, matrix ->
        require(node.stroke == null || node.strokeLineWidth <= 0f || node.strokeAlpha <= 0f) { "the icon has a stroked path" }
        require(node.fill != null) { "the icon has an unfilled path" }
        require(node.fillAlpha >= 1f) { "the icon has a translucent fill" }
        require(node.trimPathStart == 0f && node.trimPathEnd == 1f) { "the icon has a trimmed path" }
        result += flattenSubpaths(node.pathData).mapNotNull { subpath ->
            dedupe(subpath.points.map(matrix::map)).takeIf { it.size >= 3 && abs(signedArea(it)) >= MIN_AREA }
        }
    }
    return result
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

/** The vertical cut splits the play ink's width in the same proportion as the two bars' widths (operator rule,
 * 2026-11-08): usually 1:1, the middle, like the built-in morph. */
internal fun proportionalCut(play: Contour, bars: List<Contour>): Float {
    val left = play.minOf { it.x }
    val widths = bars.map { bar -> bar.maxOf { it.x } - bar.minOf { it.x } }
    return left + (play.maxOf { it.x } - left) * widths[0] / (widths[0] + widths[1])
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
