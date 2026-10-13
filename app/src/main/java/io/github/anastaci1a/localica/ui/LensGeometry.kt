package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/** The outline and the optical crown are independent: a small outline corner must
 * never turn an expanded lens into a flat plate with a bevel around its perimeter. */
internal data class LensGeometry(val bounds: Rect, val corner: Float) {
    val span get() = min(bounds.width, bounds.height) / 2f

    /** Crown coordinate and its gradient. Homothetic rounded contours avoid the
     * diagonal medial-axis ridges of an extruded nearest-edge distance field. */
    fun field(point: Offset): LensField {
        val p = point - bounds.center
        if (span <= 0f || p.getDistanceSquared() < .0000001f) return LensField(span, Offset.Zero)
        val r = corner.coerceIn(0f, span)
        val half = Offset(bounds.width / 2f, bounds.height / 2f)
        val core = half - Offset(r, r)
        val q = Offset(abs(p.x), abs(p.y))
        val t: Float
        val gradient: Offset
        if (q.y * half.x <= q.x * core.y) {
            t = q.x / half.x; gradient = Offset(1f / half.x, 0f)
        } else if (q.x * half.y <= q.y * core.x) {
            t = q.y / half.y; gradient = Offset(0f, 1f / half.y)
        } else {
            // Intersect a ray from the center with the rounded corner. The
            // rationalized quadratic root avoids subtracting two close values.
            val b = q.x * core.x + q.y * core.y
            val c = q.getDistanceSquared()
            val a = core.getDistanceSquared() - r * r
            t = c / (b + sqrt(max(b * b - a * c, 0f))).coerceAtLeast(.000001f)
            val normal = q - core * t
            val denominator = (normal.x * core.x + normal.y * core.y + t * r * r).coerceAtLeast(.000001f)
            gradient = normal / denominator
        }
        return LensField(span * (1f - t), Offset(-sign(p.x) * gradient.x * span, -sign(p.y) * gradient.y * span))
    }
}
internal data class LensField(val distance: Float, val inward: Offset)
internal data class LensRay(val offset: Offset, val normalZ: Float, val height: Float, val normalXY: Offset = Offset.Zero)

/** Shared mathematical model for both execution adapters. Shape depth changes the
 * surface normal and ray path together; there is no zoom transform or inset plate. */
internal fun lensRay(field: LensField, span: Float, depth: Float): LensRay {
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
    val shoulder = .4f
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
