package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/** The outline and the optical crown are independent: a small outline corner must
 * never turn an expanded lens into a flat plate with a bevel around its perimeter. */
internal data class LensGeometry(val bounds: Rect, val corner: Float) {
    val span get() = min(bounds.width, bounds.height) / 2f

    /** A single smooth membrane across the face, constrained by its rectangular
     * sides and circular corners. A radial gauge partitions a box into directional
     * wedges: even continuous normals then leave visible changes in curvature. */
    fun field(point: Offset): LensField {
        val p = point - bounds.center
        if (span <= 0f || p.getDistanceSquared() < .0000001f) return LensField(span, Offset.Zero)
        val r = corner.coerceIn(0f, span)
        val half = Offset(bounds.width / 2f, bounds.height / 2f)
        val core = half - Offset(r, r)
        val q = Offset(max(abs(p.x) - core.x, 0f), max(abs(p.y) - core.y, 0f))
        val outside = max(max(abs(p.x) - half.x, abs(p.y) - half.y), q.getDistance() - r)
        val sx = (1f - p.x * p.x / (half.x * half.x)).coerceAtLeast(0f)
        val sy = (1f - p.y * p.y / (half.y * half.y)).coerceAtLeast(0f)
        val rectangle = sx * sy
        val rectangleGradient = Offset(-2f * p.x * sy / (half.x * half.x), -2f * p.y * sx / (half.y * half.y))
        val roundWeight = r * r / (half.x * half.y)
        val rounded = if (r > 0f) (1f - q.getDistanceSquared() / (r * r)).coerceAtLeast(0f) else 1f
        val roundedGradient = if (r > 0f) Offset(-2f * sign(p.x) * q.x, -2f * sign(p.y) * q.y) / (r * r) else Offset.Zero
        // Weighted harmonic mean preserves either boundary's zero without a min,
        // max or angular switch across the interior. A circle stays exactly radial.
        val denominator = (1f - roundWeight) * rounded + roundWeight * rectangle
        val potential: Float
        val gradient: Offset
        if (denominator > .0000001f) {
            potential = rectangle * rounded / denominator
            gradient = (rectangleGradient * ((1f - roundWeight) * rounded * rounded) +
                roundedGradient * (roundWeight * rectangle * rectangle)) / (denominator * denominator)
        } else {
            potential = 0f
            // At a shared side both vanish; take their inward limiting derivative.
            val a = rectangleGradient.getDistance()
            val b = roundedGradient.getDistance()
            gradient = if (a > 0f && b > 0f) (rectangleGradient / a + roundedGradient / b) *
                (.5f * a * b / ((1f - roundWeight) * b + roundWeight * a)) else rectangleGradient
        }
        val radial = sqrt((1f - potential).coerceAtLeast(.0000001f))
        return LensField(if (outside > 0f) -outside else span * (1f - radial), gradient * (span / (2f * radial)))
    }
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
internal data class LensRay(val offset: Offset, val normalZ: Float, val height: Float, val normalXY: Offset = Offset.Zero)

/** Shared vertex illumination for ordinary and layered legacy lenses. */
internal fun lensReflectionColor(ray: LensRay, tone: GlassTone, power: Float = 1f): Int {
    val facing = (ray.normalXY.x * -.45f + ray.normalXY.y * -.75f + ray.normalZ * .6f) / 1.06066f
    val grazing = 1f - ray.normalZ
    val opacity = (.018f * grazing + .26f * grazing * grazing * grazing) * power *
        if (facing >= 0f) facing else -facing * .3f
    return glassGray(tone.channel(if (facing >= 0f) 1f else 0f), opacity)
}

internal fun glassGray(value: Float, opacity: Float): Int {
    val c = (value * 255f).toInt().coerceIn(0, 255)
    return ((opacity * 255f).toInt().coerceIn(0, 255) shl 24) or (c shl 16) or (c shl 8) or c
}

/** Shared mathematical model for both execution adapters. Shape depth changes the
 * surface normal and ray path together; there is no zoom transform or inset plate. */
internal fun lensRay(field: LensField, span: Float, depth: Float, flattening: Float = 0f): LensRay {
    if (field.distance < 0f || span <= 0f) return LensRay(Offset.Zero, 1f, 0f)
    val u = (1f - field.distance / span).coerceIn(0f, 1f)
    val root = sqrt(max(1f - u * u, .0004f))
    val requested = depth.coerceIn(.05f, 4f)
    val opticalDepth = requested / sqrt(1f + requested * requested / 4f)
    val crown = span * .62f * opticalDepth
    // A continuously curved shoulder carries most of the optical power. The
    // center retains finite curvature without magnifying the whole face like a
    // thick spherical lens. This is the derivative of the same height field,
    // not an independent rim displacement or a flat-center mask.
    val shoulder = .4f + .06f * flattening.coerceIn(0f, 1f)
    val height = crown * root * (1f + shoulder * u * u)
    val slope = crown / span * u / root * (1f - 2f * shoulder + 3f * shoulder * u * u)
    val gx = -field.inward.x * slope
    val gy = -field.inward.y * slope
    val z = 1f / sqrt(1f + gx * gx + gy * gy)
    val nx = gx * z
    val ny = gy * z
    // Entry and exit interfaces: a single height projection nearly cancels the
    // changing slope and looks like uniform zoom. The emerging ray bends again.
    val eta = 1f / 1.33f
    val k = sqrt(max(1f - eta * eta * (1f - z * z), 0f))
    val factor = eta * z - k
    val rz = -eta + factor * z
    val inside = Offset(nx * factor, ny * factor)
    val outside = inside * 1.33f
    val exitZ = sqrt(max(1f - outside.getDistanceSquared(), .01f))
    val offset = inside * (height / max(-rz, .12f)) + outside * (span * .32f * opticalDepth / exitZ)
    return LensRay(offset, z, height, Offset(nx, ny))
}

/** Precision noise in coordinate subtraction must not rebuild a silhouette while
 * its content simply translates. The origin retains full subpixel precision. */
internal fun localLensRegions(regions: List<SelectionRegion>, origin: Offset) = regions.map {
    val r = it.bounds.translate(-origin)
    fun stable(value: Float) = round(value * 64f) / 64f
    SelectionRegion(Rect(stable(r.left), stable(r.top), stable(r.right), stable(r.bottom)))
}

/** Exact common case, not a bounding-hull approximation: only one item or a
 * contiguous column whose rectangular bridges form one rounded outline. */
internal fun simpleLensGeometry(regions: List<SelectionRegion>, gap: Float): LensGeometry? {
    if (regions.isEmpty()) return null
    val sorted = regions.sortedBy { it.bounds.top }
    val first = sorted.first().bounds
    val radius = min(gap * 1.5f, min(first.width, first.height) / 2f)
    if (sorted.any { it.bounds.left != first.left || it.bounds.right != first.right || it.bounds.height < 2 * radius }) return null
    if (sorted.zipWithNext().any { (a, b) -> b.bounds.top - a.bounds.bottom !in 0f..gap }) return null
    return LensGeometry(Rect(first.left, first.top, first.right, sorted.last().bounds.bottom), radius)
}
