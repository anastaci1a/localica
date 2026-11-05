package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size

/** One physical outline shared by selector rendering, clipping and point ownership. */
internal data class SelectorTrackContour private constructor(val outline: RoundRect) {
    val bounds = Rect(outline.left, outline.top, outline.right, outline.bottom)
    private val radii = listOf(outline.topLeftCornerRadius, outline.topRightCornerRadius,
        outline.bottomRightCornerRadius, outline.bottomLeftCornerRadius)

    init {
        require(outline.left.isFinite() && outline.top.isFinite() && outline.right.isFinite() &&
            outline.bottom.isFinite() && outline.width.isFinite() && outline.height.isFinite())
        require(outline.width > 0f && outline.height > 0f)
        val limit = minOf(outline.width, outline.height) / 2f
        require(radii.all { it.x.isFinite() && it.y.isFinite() && it.x >= 0f && it.y >= 0f &&
            it.x <= limit && it.y <= limit })
    }

    val corner get() = outline.topLeftCornerRadius.x
    val isUniform = radii.all { it == CornerRadius(corner) }
    val maximumRadius = radii.maxOf { maxOf(it.x, it.y) }
    val opticalWeight = if (maximumRadius > 0f)
        (1f - radii.minOf { minOf(it.x, it.y) } / maximumRadius).coerceIn(0f, 1f) else 0f

    fun contains(point: Offset): Boolean {
        val b = bounds
        if (!b.contains(point)) return false
        if (isUniform) {
            // Preserve existing inclusive circular boundaries and Float operation order.
            val x = point.x.coerceIn(b.left + corner, b.right - corner)
            val y = point.y.coerceIn(b.top + corner, b.bottom - corner)
            val dx = point.x - x; val dy = point.y - y
            return dx * dx + dy * dy <= corner * corner
        }
        val left = point.x < b.center.x
        val top = point.y < b.center.y
        val radius = if (top) {
            if (left) outline.topLeftCornerRadius else outline.topRightCornerRadius
        } else if (left) outline.bottomLeftCornerRadius else outline.bottomRightCornerRadius
        if (radius.x == 0f || radius.y == 0f) return true
        val x = if (left) b.left + radius.x else b.right - radius.x
        val y = if (top) b.top + radius.y else b.bottom - radius.y
        if ((if (left) point.x >= x else point.x <= x) ||
            (if (top) point.y >= y else point.y <= y)) return true
        val dx = (point.x - x) / radius.x; val dy = (point.y - y) / radius.y
        return dx * dx + dy * dy <= 1f
    }

    fun translated(offset: Offset): SelectorTrackContour {
        require(offset.x.isFinite() && offset.y.isFinite())
        return SelectorTrackContour(RoundRect(outline.left + offset.x, outline.top + offset.y,
            outline.right + offset.x, outline.bottom + offset.y, outline.topLeftCornerRadius,
            outline.topRightCornerRadius, outline.bottomRightCornerRadius, outline.bottomLeftCornerRadius))
    }

    /** Material recordings may sample at a different resolution; translation is not deformation. */
    fun outlineAt(size: Size): RoundRect {
        require(size.width.isFinite() && size.height.isFinite() && size.width >= 0f && size.height >= 0f)
        val scale = minOf(size.width / bounds.width, size.height / bounds.height)
        fun CornerRadius.scaled() = CornerRadius(x * scale, y * scale)
        return RoundRect(0f, 0f, size.width, size.height, outline.topLeftCornerRadius.scaled(),
            outline.topRightCornerRadius.scaled(), outline.bottomRightCornerRadius.scaled(), outline.bottomLeftCornerRadius.scaled())
    }

    companion object {
        fun uniform(bounds: Rect, corner: Float): SelectorTrackContour {
            require(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() &&
                bounds.bottom.isFinite() && corner.isFinite())
            require(bounds.width > 0f && bounds.height > 0f && corner >= 0f)
            val radius = corner.coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
            return SelectorTrackContour(RoundRect(bounds, CornerRadius(radius)))
        }

        fun ends(bounds: Rect, corner: Float, free: CornerRadius, vertical: Boolean, wall: Float): SelectorTrackContour {
            require(wall.isFinite() && free.x.isFinite() && free.y.isFinite() && free.x >= 0f && free.y >= 0f)
            val base = uniform(bounds, corner)
            if (wall == 0f) return base
            val contact = CornerRadius(base.corner)
            // Ease spatial handoff toward a round lens away from either contact wall.
            val affinity = kotlin.math.abs(wall).coerceAtMost(1f)
            val opposite = CornerRadius(base.corner + (free.x.coerceAtMost(base.corner) - base.corner) * affinity,
                base.corner + (free.y.coerceAtMost(base.corner) - base.corner) * affinity)
            val start = if (wall < 0f) contact else opposite
            val end = if (wall < 0f) opposite else contact
            return SelectorTrackContour(if (vertical) RoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                start, start, end, end) else RoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                start, end, end, start))
        }
    }
}

internal fun SelectorTrackLens.contour() = freeCorner?.let {
    SelectorTrackContour.ends(bounds, corner, it, vertical, wall)
} ?: SelectorTrackContour.uniform(bounds, corner)
