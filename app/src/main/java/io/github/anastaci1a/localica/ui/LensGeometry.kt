package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/** The outline and the optical crown are independent: a small outline corner must
 * never turn an expanded lens into a flat plate with a bevel around its perimeter. */
internal data class LensGeometry(val bounds: Rect, val corner: Float) {
    val span get() = min(bounds.width, bounds.height) / 2f

    /** Signed distance (positive inside) and inward unit normal. */
    fun field(point: Offset): LensField {
        val p = point - bounds.center
        val r = corner.coerceIn(0f, span)
        val x = abs(p.x) - bounds.width / 2f + r
        val y = abs(p.y) - bounds.height / 2f + r
        val ox = max(x, 0f); val oy = max(y, 0f)
        val outside = sqrt(ox * ox + oy * oy)
        val distance = r - outside - min(max(x, y), 0f)
        val gradient = if (outside > .0001f) Offset(-sign(p.x) * ox / outside, -sign(p.y) * oy / outside)
            else if (x > y) Offset(-sign(p.x), 0f) else Offset(0f, -sign(p.y))
        return LensField(distance, gradient)
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
    val slope = crown / span * u / root
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
    val offset = inside * (crown * root / max(-rz, .12f)) + outside * (span * .32f * opticalDepth / exitZ)
    return LensRay(offset, z, crown * root, Offset(nx, ny))
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
