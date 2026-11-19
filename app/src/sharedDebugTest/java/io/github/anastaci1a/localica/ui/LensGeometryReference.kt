package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import kotlin.math.*

private const val CornerReach = 1.52866495f
    /** Distance inside the edge and the unit inward gradient. Negative outside. */
internal fun LensGeometry.field(point: Offset): LensField {
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

internal inline fun averageGlassColor(sample: (Int, Int) -> Int): androidx.compose.ui.graphics.Color {
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (y in 0 until 8) for (x in 0 until 8) {
        val pixel = sample(x, y)
        val a = (pixel ushr 24) / 255f
        red += (pixel ushr 16 and 255) / 255f * a
        green += (pixel ushr 8 and 255) / 255f * a
        blue += (pixel and 255) / 255f * a
        alpha += a
    }
    return if (alpha == 0f) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.ui.graphics.Color(red / alpha, green / alpha, blue / alpha, alpha / 64)

}



private val lensReferenceDistances = java.util.WeakHashMap<LensDistanceMap, FloatArray>()
internal fun LensDistanceMap.sample(point: Offset, path: androidx.compose.ui.graphics.Path): LensField {
        val distances = lensReferenceDistances.getOrPut(this) {
            val mask = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(mask).apply {
                scale(scale, scale); translate(-origin.x, -origin.y)
                drawPath(path.asAndroidPath(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            mask.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            mask.recycle()
            selectionDistances(pixels, bitmap.width, bitmap.height)
        }
        // Match the linear bitmap sampler: samples live at texel centers, and
        // normals interpolate with distance rather than jumping at each cell.
        val p = (point - origin) * scale - Offset(.5f, .5f)
        fun d(x: Int, y: Int) = distances[y.coerceIn(0, bitmap.height - 1) * bitmap.width + x.coerceIn(0, bitmap.width - 1)]
        val x = floor(p.x).toInt(); val y = floor(p.y).toInt()
        val fx = p.x - x; val fy = p.y - y
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        val distance = mix(mix(d(x, y), d(x + 1, y), fx), mix(d(x, y + 1), d(x + 1, y + 1), fx), fy)
        fun gx(x: Int, y: Int) = ((d(x + 1, y) - d(x - 1, y)) * .5f).coerceIn(-1f, 1f)
        fun gy(x: Int, y: Int) = ((d(x, y + 1) - d(x, y - 1)) * .5f).coerceIn(-1f, 1f)
        return LensField(max(distance - .5f, 0f) / scale, Offset(
            mix(mix(gx(x, y), gx(x + 1, y), fx), mix(gx(x, y + 1), gx(x + 1, y + 1), fx), fy),
            mix(mix(gy(x, y), gy(x + 1, y), fx), mix(gy(x, y + 1), gy(x + 1, y + 1), fx), fy)))
    }

internal fun GlassSilhouetteFrame.opticalField(point: Offset): LensField {
        val analytic = description.circles?.let { centers ->
            pairedCircleLensField(point, centers, geometry.span, description.fieldWeight)
        } ?: geometry.field(point)
        val boundary = description.splitInner?.let { splitPillDistanceField(point, bounds, it) } ?: field?.sample(point, path)
        return boundary?.let { blendLensFields(analytic, it, description.fieldWeight) } ?: analytic
    }

internal fun glassMorphSpring(speed: Float) = androidx.compose.animation.core.spring<Float>(1f, 90f * speed * speed, visibilityThreshold = .0001f)
