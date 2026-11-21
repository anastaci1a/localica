package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import kotlin.math.*

internal data class LensField(val distance: Float, val inward: Offset)

internal fun LensGeometry.field(point: Offset): LensField =
    if (bodies.size == 1) bodies.single().field(point)
    else bodies.map { it.field(point) }.reduce(::composeLensReference)

internal fun Float.lensHalf(): Float {
    if (!isFinite() || this == 0f) return this
    val spacing = Math.scalb(1.0, max(-24, Math.getExponent(toDouble()) - 10))
    val rounded = (Math.rint(this / spacing) * spacing).toFloat()
    return if (abs(rounded) > 65504f) Math.copySign(Float.POSITIVE_INFINITY, rounded) else rounded
}

private fun LensField.roundedToHalf() = LensField(distance.lensHalf(),
    Offset(inward.x.lensHalf(), inward.y.lensHalf()))

/** Reference fold from the Compose 58 binary record, converted to the renderer's positive-inside sign. */
internal fun composeLensReference(first: LensField, second: LensField): LensField {
    val a = first.roundedToHalf()
    val b = second.roundedToHalf()
    val dot = (a.inward.x * b.inward.x + a.inward.y * b.inward.y).lensHalf()
    val alignment = (.5f - (dot * .5f).lensHalf()).lensHalf().coerceIn(0f, 1f)
    val k = (alignment * .0001f.lensHalf()).lensHalf()
    val offset = ((a.distance - b.distance).lensHalf() * .5f).lensHalf()
    val weight = ((offset / k).lensHalf() + .5f).lensHalf()
    if (weight.isNaN()) {
        // This arithmetic witness only uses singular pairs when both operands encode the same field.
        check(a.distance == b.distance && a.inward.x == b.inward.x && a.inward.y == b.inward.y)
        return a
    }
    val h = weight.coerceIn(0f, 1f)
    fun mix(from: Float, to: Float) = from + (to - from) * h
    val correction = ((k * h).lensHalf() * (1f - h).lensHalf()).lensHalf()
    return LensField(mix(b.distance, a.distance) + correction,
        Offset(mix(b.inward.x, a.inward.x), mix(b.inward.y, a.inward.y))).roundedToHalf()
}

/** Independent segment projection and angular minimization for each fitted body. */
private fun RoundRect.field(point: Offset): LensField {
    val b = this
    val r = b.topLeftCornerRadius
    if (r.x == r.y && r == b.topRightCornerRadius &&
        r == b.bottomRightCornerRadius && r == b.bottomLeftCornerRadius) {
        val nearestMiddle = Offset(point.x.coerceIn(b.left + r.x, b.right - r.x),
            point.y.coerceIn(b.top + r.y, b.bottom - r.y))
        val offset = point - nearestMiddle
        val length = offset.getDistance()
        if (length > 0f) return LensField(r.x - length, -offset / length)
        val rect = b.boundingRect
        return listOf(LensField(rect.bottom - point.y, Offset(0f, -1f)),
            LensField(point.y - rect.top, Offset(0f, 1f)), LensField(rect.right - point.x, Offset(-1f, 0f)),
            LensField(point.x - rect.left, Offset(1f, 0f))).minBy { it.distance }
    }
    val inside = contains(point)
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
    val tl = topLeftCornerRadius
    val tr = topRightCornerRadius
    val br = bottomRightCornerRadius
    val bl = bottomLeftCornerRadius
    edge(left + bl.x, bottom, right - br.x, bottom, Offset(0f, -1f))
    edge(right, top + tr.y, right, bottom - br.y, Offset(-1f, 0f))
    edge(left + tl.x, top, right - tr.x, top, Offset(0f, 1f))
    edge(left, top + tl.y, left, bottom - bl.y, Offset(1f, 0f))
    arc(right, bottom, br, 1, 1)
    arc(left, bottom, bl, -1, 1)
    arc(right, top, tr, 1, -1)
    arc(left, top, tl, -1, -1)
    val sign = if (inside) 1 else -1
    val distance = sqrt(best)
    val inward = if (distance > 1e-6) Offset(((point.x - closestX) * sign / distance).toFloat(),
        ((point.y - closestY) * sign / distance).toFloat()) else normal
    return LensField((distance * sign).toFloat(), inward)
}
