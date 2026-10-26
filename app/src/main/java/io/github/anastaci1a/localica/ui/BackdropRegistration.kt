package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max

/** An affine mapping between two recording-local pixel spaces, not between their bounds. */
@ConsistentCopyVisibility
internal data class BackdropRegistration private constructor(
    val xx: Float, val xy: Float, val yx: Float, val yy: Float,
    val tx: Float, val ty: Float
) {
    fun map(point: Offset): Offset = Offset(
        xx * point.x + xy * point.y + tx,
        yx * point.x + yy * point.y + ty
    )

    fun inverse(): BackdropRegistration? {
        val determinant = xx * yy - xy * yx
        return create(yy / determinant, -xy / determinant, -yx / determinant, xx / determinant,
            (xy * ty - yy * tx) / determinant, (yx * tx - xx * ty) / determinant)
    }

    /** Apply this mapping first, then [next]. Common ancestor transforms cancel here. */
    fun followedBy(next: BackdropRegistration): BackdropRegistration? = create(
        next.xx * xx + next.xy * yx, next.xx * xy + next.xy * yy,
        next.yx * xx + next.yy * yx, next.yx * xy + next.yy * yy,
        next.xx * tx + next.xy * ty + next.tx, next.yx * tx + next.yy * ty + next.ty
    )

    fun corners(bounds: Rect): List<Offset> = listOf(
        map(bounds.topLeft), map(bounds.topRight), map(bounds.bottomRight), map(bounds.bottomLeft)
    )

    companion object {
        val Identity = requireNotNull(create(1f, 0f, 0f, 1f, 0f, 0f))

        fun create(xx: Float, xy: Float, yx: Float, yy: Float, tx: Float, ty: Float): BackdropRegistration? {
            if (!listOf(xx, xy, yx, yy, tx, ty).all { it.isFinite() }) return null
            val determinant = xx.toDouble() * yy - xy.toDouble() * yx
            val magnitude = max(abs(xx.toDouble() * yy), abs(xy.toDouble() * yx))
            // Reject singular/ill-conditioned bases explicitly; never replace one with identity.
            if (magnitude == 0.0 || abs(determinant) <= magnitude * 1e-6 || !determinant.isFinite()) return null
            val value = BackdropRegistration(xx, xy, yx, yy, tx, ty)
            // The float draw adapters must also be able to represent the inverse.
            val d = xx * yy - xy * yx
            if (!d.isFinite() || d == 0f || !listOf(yy / d, xy / d, yx / d, xx / d,
                    (xy * ty - yy * tx) / d, (yx * tx - xx * ty) / d).all { it.isFinite() }) return null
            return value
        }

        /** Sample the whole local extent for precision, and reject perspective/non-affine maps. */
        fun fromMapping(width: Float, height: Float, map: (Offset) -> Offset): BackdropRegistration? {
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
            val origin = map(Offset.Zero)
            val right = map(Offset(width, 0f))
            val bottom = map(Offset(0f, height))
            val opposite = map(Offset(width, height))
            if (!listOf(origin, right, bottom, opposite).all { it.x.isFinite() && it.y.isFinite() }) return null
            val result = create((right.x - origin.x) / width, (bottom.x - origin.x) / height,
                (right.y - origin.y) / width, (bottom.y - origin.y) / height, origin.x, origin.y) ?: return null
            val expected = result.map(Offset(width, height))
            val span = max(max(abs(right.x - origin.x), abs(right.y - origin.y)),
                max(abs(bottom.x - origin.x), abs(bottom.y - origin.y)))
            val tolerance = max(1f, span) * 1e-4f
            if (abs(expected.x - opposite.x) > tolerance || abs(expected.y - opposite.y) > tolerance) return null
            return result
        }
    }
}

/** Rounded recording bounds are convex, so containing all mapped corners contains the footprint. */
internal fun backdropContainsFootprint(width: Float, height: Float, corner: Float, corners: List<Offset>): Boolean {
    if (!width.isFinite() || !height.isFinite() || !corner.isFinite() || width <= 0f || height <= 0f || corners.size != 4) return false
    val inset = corner.coerceIn(0f, minOf(width, height) / 2f)
    return corners.all { point ->
        val x = point.x; val y = point.y
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > width || y > height) false
        else {
            val dx = (inset - x).coerceAtLeast(0f) + (x - (width - inset)).coerceAtLeast(0f)
            val dy = (inset - y).coerceAtLeast(0f) + (y - (height - inset)).coerceAtLeast(0f)
            dx * dx + dy * dy <= inset * inset
        }
    }
}

/** Texture-only mapping and cache identity; optical vertices and rays remain consumer-local. */
internal data class BackdropSampleMapping(
    val sourceSize: IntSize,
    val sourceOffset: Offset,
    val registration: BackdropRegistration? = null
) {
    fun texture(point: Offset, imageSize: IntSize): Offset {
        val source = registration?.map(point) ?: (point + sourceOffset)
        return Offset(source.x * (imageSize.width.toFloat() / sourceSize.width.coerceAtLeast(1)),
            source.y * (imageSize.height.toFloat() / sourceSize.height.coerceAtLeast(1)))
    }
}
