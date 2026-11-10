package io.github.anastaci1a.localica.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Optical vertical centring (#724). A glyph whose ink is bottom-heavy looks low even when its bounding box is
 * centred, so every pack glyph moves vertically [OPTICAL_CENTRING] of the way from its bounding-box centre toward
 * its ink-weighted centre. Symmetric glyphs measure no offset and keep their exact geometry.
 */
internal const val OPTICAL_CENTRING = .5f

/** Corrections below 0.05 dp of a 24 dp icon are sampling noise, not shape: such glyphs stay exactly where drawn. */
internal const val OPTICAL_DEADBAND = .05f / 24f

/** Vertical ink extent and ink-weighted centre, in the measured canvas's units, y down. */
internal class InkMetrics(val top: Float, val bottom: Float, val centroidY: Float) {
    val boxCentreY: Float get() = (top + bottom) / 2f
    /** Positive when the ink sits below its own box centre. */
    val inkOffsetY: Float get() = centroidY - boxCentreY
}

/** The vertical move for a glyph on a canvas [height] units tall; it never pushes ink across the canvas edge. */
internal fun opticalShift(metrics: InkMetrics, height: Float): Float {
    val shift = -OPTICAL_CENTRING * metrics.inkOffsetY
    if (abs(shift) < OPTICAL_DEADBAND * height) return 0f
    return shift.coerceIn(-max(0f, metrics.top), max(0f, height - metrics.bottom))
}

/** The same glyph, optically centred; returns this instance unchanged when there is nothing to move. */
internal fun ImageVector.opticallyCentred(): ImageVector {
    val shift = opticalShiftY()
    return if (shift == 0f) this else translatedY(shift)
}

/** The optical move in viewport units, or 0 for symmetric or empty glyphs. */
internal fun ImageVector.opticalShiftY(): Float = inkMetrics()?.let { opticalShift(it, viewportHeight) } ?: 0f

/** Copies the vector tree under one extra group translated by [dy] viewport units. */
internal fun ImageVector.translatedY(dy: Float): ImageVector {
    val builder = ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight,
        tintColor, tintBlendMode, autoMirror)
    builder.addGroup(name = "optical-centre", translationY = dy)
    fun copy(group: VectorGroup) {
        builder.addGroup(group.name, group.rotation, group.pivotX, group.pivotY, group.scaleX, group.scaleY,
            group.translationX, group.translationY, group.clipPathData)
        for (node in group) when (node) {
            is VectorGroup -> copy(node)
            is VectorPath -> builder.addPath(node.pathData, node.pathFillType, node.name, node.fill, node.fillAlpha,
                node.stroke, node.strokeAlpha, node.strokeLineWidth, node.strokeLineCap, node.strokeLineJoin,
                node.strokeLineMiter, node.trimPathStart, node.trimPathEnd, node.trimPathOffset)
        }
        builder.clearGroup()
    }
    copy(root)
    builder.clearGroup()
    return builder.build()
}

private const val INK_SAMPLES = 512

/**
 * Ink coverage of the vector's own paths, sampled on a [samples]-square grid over the viewport: fills by scanline
 * with their fill rule, strokes as the area within half the stroke width of the centre line (caps as drawn, joins
 * as round). It measures geometry only; drawing still uses the vector paths.
 */
internal fun ImageVector.inkMetrics(samples: Int = INK_SAMPLES): InkMetrics? {
    val grid = InkGrid(samples, viewportWidth, viewportHeight)
    forEachPath(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)) { path, matrix ->
        val subpaths = flattenSubpaths(path.pathData).map { Subpath(it.points.map(matrix::map), it.closed) }
        val fill = path.fillAlpha * path.fill.alpha()
        if (fill > 0f) grid.fill(subpaths, path.pathFillType == PathFillType.EvenOdd, fill)
        val stroke = path.strokeAlpha * path.stroke.alpha()
        val width = path.strokeLineWidth * sqrt(abs(matrix[0] * matrix[3] - matrix[1] * matrix[2]))
        if (stroke > 0f && width > 0f) grid.stroke(subpaths, width / 2f, path.strokeLineCap, stroke)
    }
    val sampled = grid.metrics() ?: return null
    val bounds = geometricInkBounds() ?: return sampled
    return InkMetrics(bounds.first, bounds.second, sampled.centroidY)
}

private fun Brush?.alpha(): Float = when (this) {
    null -> 0f
    is SolidColor -> value.alpha
    else -> 1f
}

/** Alpha ink metrics of a [width] × [height] mask, in pixels; [alpha] holds 0–255 per pixel, row-major. */
internal fun alphaInkMetrics(alpha: IntArray, width: Int, height: Int): InkMetrics? {
    require(alpha.size == width * height)
    var total = 0.0; var moment = 0.0; var top = -1; var bottom = -1
    for (y in 0 until height) {
        var row = 0L
        for (x in 0 until width) row += alpha[y * width + x]
        if (row == 0L) continue
        if (top < 0) top = y
        bottom = y
        total += row; moment += row * (y + .5)
    }
    if (total == 0.0) return null
    return InkMetrics(top.toFloat(), bottom + 1f, (moment / total).toFloat())
}

private class InkGrid(val n: Int, width: Float, height: Float) {
    private val sx = width / n
    private val sy = height / n
    private val coverage = FloatArray(n * n)

    private fun mark(row: Int, column: Int, alpha: Float) {
        val i = row * n + column
        if (alpha > coverage[i]) coverage[i] = alpha
    }

    fun fill(subpaths: List<Subpath>, evenOdd: Boolean, alpha: Float) {
        val edges = subpaths.flatMap { s -> s.points.indices.map { s.points[it] to s.points[(it + 1) % s.points.size] } }
            .filter { (a, b) -> a.y != b.y }
        if (edges.isEmpty()) return
        val crossings = ArrayList<Pair<Float, Int>>()
        for (row in 0 until n) {
            val y = (row + .5f) * sy
            crossings.clear()
            for ((a, b) in edges) {
                if (y < min(a.y, b.y) || y >= max(a.y, b.y)) continue
                crossings += (a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)) to (if (b.y > a.y) 1 else -1)
            }
            crossings.sortBy { it.first }
            var winding = 0
            for (i in 0 until crossings.size - 1) {
                winding += crossings[i].second
                val inside = if (evenOdd) winding % 2 != 0 else winding != 0
                if (!inside) continue
                val first = max(0, ceil(crossings[i].first / sx - .5f).toInt())
                val last = min(n - 1, ceil(crossings[i + 1].first / sx - .5f).toInt() - 1)
                for (column in first..last) mark(row, column, alpha)
            }
        }
    }

    fun stroke(subpaths: List<Subpath>, half: Float, cap: StrokeCap, alpha: Float) {
        for (subpath in subpaths) {
            val points = subpath.points.fold(mutableListOf<Offset>()) { list, p -> if (list.lastOrNull() != p) list += p; list }
            if (subpath.closed && points.size > 1 && points.first() == points.last()) points.removeAt(points.lastIndex)
            if (points.size == 1) {
                // A zero-length drawn segment is a dot; a lone moveto draws nothing.
                if (subpath.points.size > 1 && cap != StrokeCap.Butt) segment(points[0], points[0], half, cap, startCap = true, endCap = true, alpha)
                continue
            }
            val count = if (subpath.closed) points.size else points.size - 1
            for (i in 0 until count) {
                val open = !subpath.closed
                segment(points[i], points[(i + 1) % points.size], half, cap,
                    startCap = open && i == 0, endCap = open && i == count - 1, alpha)
            }
        }
    }

    /** One stroke segment; inner ends are round joins, outer ends of open subpaths use the stroke's cap. */
    private fun segment(p: Offset, q: Offset, half: Float, cap: StrokeCap, startCap: Boolean, endCap: Boolean, alpha: Float) {
        val dx = q.x - p.x; val dy = q.y - p.y
        val length = sqrt(dx * dx + dy * dy)
        val ux = if (length > 0f) dx / length else 1f; val uy = if (length > 0f) dy / length else 0f
        val reach = half * 1.5f // square caps reach the corner diagonal
        val rows = max(0, floor((min(p.y, q.y) - reach) / sy).toInt())..min(n - 1, ceil((max(p.y, q.y) + reach) / sy).toInt())
        val columns = max(0, floor((min(p.x, q.x) - reach) / sx).toInt())..min(n - 1, ceil((max(p.x, q.x) + reach) / sx).toInt())
        for (row in rows) {
            val y = (row + .5f) * sy - p.y
            for (column in columns) {
                val x = (column + .5f) * sx - p.x
                val along = x * ux + y * uy
                val across = abs(x * uy - y * ux)
                val inside = when {
                    along < 0f && startCap -> capCovers(cap, -along, across, half)
                    along > length && endCap -> capCovers(cap, along - length, across, half)
                    else -> {
                        val t = along.coerceIn(0f, length)
                        val ex = x - ux * t; val ey = y - uy * t
                        ex * ex + ey * ey <= half * half
                    }
                }
                if (inside) mark(row, column, alpha)
            }
        }
    }

    private fun capCovers(cap: StrokeCap, beyond: Float, across: Float, half: Float) = when (cap) {
        StrokeCap.Butt -> false
        StrokeCap.Square -> beyond <= half && across <= half
        else -> beyond * beyond + across * across <= half * half
    }

    fun metrics(): InkMetrics? {
        var total = 0.0; var moment = 0.0; var top = -1; var bottom = -1
        for (row in 0 until n) {
            var sum = 0.0
            for (column in 0 until n) sum += coverage[row * n + column]
            if (sum == 0.0) continue
            if (top < 0) top = row
            bottom = row
            total += sum; moment += sum * (row + .5) * sy
        }
        if (total == 0.0) return null
        return InkMetrics(top * sy, (bottom + 1) * sy, (moment / total).toFloat())
    }
}
