package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import kotlin.math.*

internal data class LensField(val distance: Float, val inward: Offset)

/** Tests Euclidean distance to the boundary using direct segment projection and angular minimization. */
internal fun LensGeometry.field(point: Offset): LensField {
    val outlines = bodies
    val b = outlines.first()
    val r = b.topLeftCornerRadius
    if (outlines.size == 1 && r.x == r.y && r == b.topRightCornerRadius &&
        r == b.bottomRightCornerRadius && r == b.bottomLeftCornerRadius) {
        val nearestMiddle = Offset(point.x.coerceIn(b.left + r.x, b.right - r.x),
            point.y.coerceIn(b.top + r.y, b.bottom - r.y))
        val offset = point - nearestMiddle
        val length = offset.getDistance()
        if (length > 0f) return LensField(r.x - length, -offset / length)
        val rect = b.boundingRect
        return listOf(LensField(rect.bottom - point.y, Offset(0f, -1f)),
            LensField(rect.right - point.x, Offset(-1f, 0f)), LensField(point.y - rect.top, Offset(0f, 1f)),
            LensField(point.x - rect.left, Offset(1f, 0f))).minBy { it.distance }
    }
    val inside = outlines.any { it.contains(point) }
    var best = Double.POSITIVE_INFINITY
    var closestX = 0.0
    var closestY = 0.0
    var normal = Offset.Zero
    fun offer(x: Double, y: Double, inward: Offset) {
        val square = (point.x - x).pow(2) + (point.y - y).pow(2)
        if (square < best) {
            best = square
            closestX = x
            closestY = y
            normal = inward
        }
    }
    fun edge(x0: Float, y0: Float, x1: Float, y1: Float, inward: Offset) {
        val x = point.x.coerceIn(min(x0, x1), max(x0, x1))
        val y = point.y.coerceIn(min(y0, y1), max(y0, y1))
        offer(x.toDouble(), y.toDouble(), inward)
    }
    fun arc(x: Float, y: Float, radius: CornerRadius, dirX: Int, dirY: Int) {
        if (radius.x <= 0f || radius.y <= 0f) return
        val a = radius.x.toDouble()
        val b = radius.y.toDouble()
        val cx = x - dirX * a
        val cy = y - dirY * b
        val u = dirX * (point.x - cx)
        val v = dirY * (point.y - cy)
        if ((u - u.coerceIn(0.0, a)).pow(2) + (v - v.coerceIn(0.0, b)).pow(2) >= best) return
        fun candidate(t: Double) {
            val xNormal = cos(t)
            val yNormal = sin(t)
            val n = Offset((-dirX * b * xNormal).toFloat(), (-dirY * a * yNormal).toFloat())
            offer(cx + dirX * a * xNormal, cy + dirY * b * yNormal, n / n.getDistance())
        }
        if (a == b) {
            candidate(atan2(v.coerceAtLeast(0.0), u.coerceAtLeast(0.0)))
            candidate(0.0)
            candidate(PI / 2)
        } else {
            fun error(t: Double) = (u - a * cos(t)).pow(2) + (v - b * sin(t)).pow(2)
            candidate(0.0)
            candidate(PI / 2)
            for (slice in 0 until 8) {
                var low = slice * PI / 16
                var high = (slice + 1) * PI / 16
                repeat(35) {
                    val l = (2 * low + high) / 3
                    val h = (low + 2 * high) / 3
                    if (error(l) <= error(h)) high = h else low = l
                }
                candidate((low + high) / 2)
            }
        }
    }
    val neighbor = outlines.getOrNull(1)
    val cut = neighbor?.takeIf { b.right == it.left }?.let {
        max(b.top + b.topRightCornerRadius.y, it.top + it.topLeftCornerRadius.y) to
            min(b.bottom - b.bottomRightCornerRadius.y, it.bottom - it.bottomLeftCornerRadius.y)
    }?.takeIf { it.first < it.second }
    outlines.forEachIndexed { index, body ->
        val (tl, tr, br, bl) = listOf(body.topLeftCornerRadius, body.topRightCornerRadius,
            body.bottomRightCornerRadius, body.bottomLeftCornerRadius)
        val l = body.left
        val t = body.top
        val r = body.right
        val b = body.bottom
        fun vertical(x: Float, lo: Float, hi: Float, outward: Float, trim: Pair<Float, Float>?) {
            val inward = Offset(-outward, 0f)
            if (trim == null) edge(x, lo, x, hi, inward)
            else {
                if (lo < trim.first) edge(x, lo, x, trim.first, inward)
                if (trim.second < hi) edge(x, trim.second, x, hi, inward)
            }
        }
        edge(l + bl.x, b, r - br.x, b, Offset(0f, -1f))
        vertical(r, t + tr.y, b - br.y, 1f, cut.takeIf { index == 0 })
        edge(l + tl.x, t, r - tr.x, t, Offset(0f, 1f))
        vertical(l, t + tl.y, b - bl.y, -1f, cut.takeIf { index == 1 })
        arc(r, b, br, 1, 1)
        arc(l, b, bl, -1, 1)
        arc(r, t, tr, 1, -1)
        arc(l, t, tl, -1, -1)
    }
    val sign = if (inside) 1 else -1
    val distance = sqrt(best)
    val inward = if (distance > 1e-6) Offset(((point.x - closestX) * sign / distance).toFloat(),
        ((point.y - closestY) * sign / distance).toFloat()) else normal
    return LensField((distance * sign).toFloat(), inward)
}
