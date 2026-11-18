package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/** A rounded rectangle with continuous corners. The weights blend each axis toward a
 * circular arc as that side approaches the corner's reach; a capsule's short axis is round. */
internal data class LensGeometry(val bounds: Rect, val corner: Float) {
    val span get() = min(bounds.width, bounds.height) / 2f
    val radius get() = corner.coerceIn(.01f, max(span, .01f))
    val weights get() = Offset(cornerWeight(bounds.width), cornerWeight(bounds.height))
    private fun cornerWeight(side: Float) =
        (side.toDouble() * -1.44577851758675 / (radius * CornerReach) + 2.8915570351735).coerceIn(0.0, 1.0).toFloat()

    /** Distance inside the edge and the unit inward gradient. Negative outside. */
    fun field(point: Offset): LensField {
        val p = point - bounds.center
        val r = radius
        val reach = r * CornerReach
        val (wx, wy) = weights
        val rho = reach + (r - reach) * max(wx, wy)
        val ex = abs(p.x) - bounds.width / 2f
        val ey = abs(p.y) - bounds.height / 2f
        val qx = rho + ex
        val qy = rho + ey
        val vx = max(0f, (ex + reach) / reach)
        val vy = max(0f, (ey + reach) / reach)
        val length = sqrt(vx * vx + vy * vy)
        val t = if (max(vx, vy) == 0f) 0f else (min(vx, vy) / max(vx, vy)).coerceIn(0f, 1f)
        val poly = (((3.15600991f - t * .926054f) * t - 3.64122009f) * t + 1.26803005f) * t + .268530995f
        val a = length + 1f + -1f / (1f - t * t * length.coerceIn(0f, 1f) * poly)
        val bx = max(0f, CornerReach * vx - .528665006f)
        val by = max(0f, CornerReach * vy - .528665006f)
        val b = sqrt(bx * bx + by * by) * .654165566f + .345834434f
        val ax = a + (b - a) * wx
        val ay = a + (b - a) * wy
        val side = if (vy > vx) 1f else -1f
        val blend = (.5f - side + side * t).coerceIn(0f, 1f)
        val distance = min(0f, max(qx, qy)) + reach * (ax + (ay - ax) * blend - 1f)
        val nx = max(qx, 0f)
        val ny = max(qy, 0f)
        val n = sqrt(nx * nx + ny * ny)
        val normal = if (n > 0f) Offset(nx / n, ny / n) else if (qx > qy) Offset(1f, 0f) else Offset(0f, 1f)
        return LensField(-distance, Offset(if (p.x < 0f) normal.x else -normal.x, if (p.y < 0f) normal.y else -normal.y))
    }
}
private const val CornerReach = 1.52866495f
internal data class LensField(val distance: Float, val inward: Offset)
internal fun circleLensField(point: Offset, center: Offset, radius: Float): LensField {
    val local = point - center
    val distance = local.getDistance()
    return LensField(radius - distance, if (distance > .000001f) -local / distance else Offset.Zero)
}
internal fun blendLensFields(from: LensField, to: LensField, weight: Float): LensField {
    val w = weight.coerceIn(0f, 1f)
    return LensField(from.distance + (to.distance - from.distance) * w,
        from.inward + (to.inward - from.inward) * w)
}

/** Exact exposed boundary of a symmetric joined split pill. Fold into its top
 * left quarter: only two arcs and their connecting horizontal edge remain.
 * The shared vertical edge is interior, so it must never refract as a seam. */
internal fun splitPillDistanceField(point: Offset, bounds: Rect, innerRadius: Float): LensField {
    val center = bounds.center
    val originalRadius = bounds.height / 2f
    val originalInner = innerRadius.coerceIn(0f, originalRadius)
    val fit = min(1f, bounds.width / 2f / (originalRadius + originalInner).coerceAtLeast(.000001f))
    val radius = originalRadius * fit
    val inner = originalInner * fit
    val delta = point - center
    val p = center - Offset(abs(delta.x), abs(delta.y))
    var closest = Offset(p.x.coerceIn(bounds.left + radius, max(bounds.left + radius, center.x - inner)), bounds.top)
    var normal = Offset(0f, 1f)
    var distanceSquared = (p - closest).getDistanceSquared()
    fun arc(c: Offset, r: Float, horizontal: Float) {
        if (r <= .000001f) return
        val local = p - c
        val q = Offset(max(local.x * horizontal, 0f), max(-local.y, 0f))
        val length = q.getDistance()
        val edge = if (length > .000001f) c + Offset(q.x * horizontal, -q.y) * (r / length)
            else {
                val a = c + Offset(horizontal * r, 0f)
                val b = c + Offset(0f, -r)
                if ((p - a).getDistanceSquared() < (p - b).getDistanceSquared()) a else b
            }
        val square = (p - edge).getDistanceSquared()
        if (square < distanceSquared) {
            closest = edge; distanceSquared = square
            val inward = c - edge
            normal = inward / inward.getDistance().coerceAtLeast(.000001f)
        }
    }
    val side = Offset(bounds.left, p.y.coerceIn(bounds.top + radius, center.y))
    val sideSquare = (p - side).getDistanceSquared()
    if (sideSquare < distanceSquared) { closest = side; distanceSquared = sideSquare; normal = Offset(1f, 0f) }
    arc(Offset(bounds.left + radius, bounds.top + radius), radius, -1f)
    arc(Offset(center.x - inner, bounds.top + inner), inner, 1f)
    val outerLocal = p - Offset(bounds.left + radius, bounds.top + radius)
    val innerLocal = p - Offset(center.x - inner, bounds.top + inner)
    val inside = p.x >= bounds.left && p.y >= bounds.top &&
        (p.x >= bounds.left + radius || p.y >= bounds.top + radius || outerLocal.getDistanceSquared() <= radius * radius) &&
        (p.x <= center.x - inner || p.y >= bounds.top + inner || innerLocal.getDistanceSquared() <= inner * inner)
    val distance = sqrt(distanceSquared)
    val signed = if (inside) 1f else -1f
    val onBoundary = distance <= max(.0001f, radius * .00001f)
    val gradient = if (!onBoundary) (p - closest) * (signed / distance) else normal
    fun unfold(value: Float, offset: Float) = if (abs(offset) < .000001f) 0f else if (offset > 0f) -value else value
    return LensField(if (onBoundary) 0f else distance * signed, Offset(unfold(gradient.x, delta.x), unfold(gradient.y, delta.y)))
}
internal fun pairedCircleLensField(point: Offset, centers: Pair<Offset, Offset>, radius: Float, bridge: Float): LensField {
    val (a, b) = centers
    val axis = b - a
    val length = axis.getDistance()
    if (length < .000001f) return circleLensField(point, a, radius)
    val direction = axis / length
    val local = point - (a + b) / 2f
    val along = local.x * direction.x + local.y * direction.y
    val width = radius * bridge.coerceIn(0f, 1f)
    if (width < .000001f) return circleLensField(point, if (along <= 0f) a else b, radius)
    val t = (.5f + along / (2f * width)).coerceIn(0f, 1f)
    val first = circleLensField(point, a, radius)
    val second = circleLensField(point, b, radius)
    val blended = blendLensFields(first, second, t * t * (3f - 2f * t))
    // Include the spatial derivative of the blending weight, so the neck's
    // surface and its refracting normal describe the same smooth field.
    return blended.copy(inward = blended.inward + direction *
        ((second.distance - first.distance) * 3f * t * (1f - t) / width))
}

/** Background refraction, in dp. Each profile displaces along the inward gradient by
 * amount * (1 - sqrt(t(2 - t))), t = inside / height. The outer sample mixes in only
 * within the outermost dp of the edge. */
internal object GlassRefraction {
    const val InnerAmount = -38.4f
    const val InnerHeight = 12f
    const val OuterAmount = 9.6f
    const val OuterHeight = 6f
    const val OuterOpacity = .3f
    const val OuterDistance = -1f
    const val OuterDistanceEnd = -.5f
}

/** Precision noise in coordinate subtraction must not rebuild a silhouette while
 * its content simply translates. The origin retains full subpixel precision. */
internal fun localLensRegions(regions: List<SelectionRegion>, origin: Offset,
    previous: List<SelectionRegion>? = null): List<SelectionRegion> {
    var result: MutableList<SelectionRegion>? = if(previous?.size==regions.size)null else ArrayList(regions.size)
    fun stable(value: Float) = round(value * 64f) / 64f
    for(i in regions.indices) {
        val region=regions[i];val bounds=region.bounds
        val l=stable(bounds.left-origin.x);val t=stable(bounds.top-origin.y)
        val r=stable(bounds.right-origin.x);val b=stable(bounds.bottom-origin.y)
        val old=previous?.getOrNull(i)
        if(old!=null && old.bounds.left.toBits()==l.toBits() && old.bounds.top.toBits()==t.toBits() &&
            old.bounds.right.toBits()==r.toBits() && old.bounds.bottom.toBits()==b.toBits() && old.corner==region.corner) {
            result?.add(old)
        } else {
            if(result==null)result=ArrayList<SelectionRegion>(regions.size).also { next -> for(j in 0 until i)next.add(previous!![j]) }
            result.add(SelectionRegion(Rect(l,t,r,b),region.corner))
        }
    }
    return result ?: previous ?: emptyList()
}

/** Exact common case, not a bounding-hull approximation: one item or an aligned
 * contiguous chain whose rectangular bridges form one rounded outline. */
internal fun simpleLensGeometry(regions: List<SelectionRegion>, gap: Float): LensGeometry? {
    if (regions.isEmpty() || !gap.isFinite() || gap < 0f || regions.any {
        val r = it.bounds
        !listOf(r.left, r.top, r.right, r.bottom, r.width, r.height).all { value -> value.isFinite() } ||
            r.width <= 0f || r.height <= 0f
    }) return null
    val sorted = regions.sortedBy { it.bounds.top }
    val first = sorted.first().bounds
    val radius = sorted.first().radius(gap)
    if (sorted.all { it.bounds.left == first.left && it.bounds.right == first.right && it.bounds.height >= 2 * radius && it.radius(gap) == radius } &&
        sorted.zipWithNext().all { (a, b) -> b.bounds.top - a.bounds.bottom in 0f..gap }) {
        return LensGeometry(Rect(first.left, first.top, first.right, sorted.last().bounds.bottom), radius)
    }
    val row = regions.sortedBy { it.bounds.left }
    val left = row.first().bounds
    val rowRadius = row.first().radius(gap)
    // The existing contour bridge extends gap*1.5 into BOTH neighbors. Keep
    // those shoulders inside each item's straight core, never its outside caps.
    // This deliberately declines narrow/unequal-radius cases rather than routing
    // a connected shape through its merely enclosing rectangle.
    val minimumWidth = gap * 1.5f * 2f
    if (!minimumWidth.isFinite() || row.any { it.bounds.top != left.top || it.bounds.bottom != left.bottom ||
        it.bounds.width < minimumWidth || it.radius(gap) != rowRadius }) return null
    if (row.zipWithNext().any { (a, b) -> b.bounds.left - a.bounds.right !in 0f..gap }) return null
    return LensGeometry(Rect(left.left, left.top, row.last().bounds.right, left.bottom), rowRadius)
}
