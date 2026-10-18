package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect

/** One physical outline for future selector adapters; uniform contours retain today's geometry. */
internal data class SelectorTrackContour private constructor(val outline: RoundRect) {
    val bounds get() = Rect(outline.left, outline.top, outline.right, outline.bottom)

    init {
        require(outline.left.isFinite() && outline.top.isFinite() && outline.right.isFinite() &&
            outline.bottom.isFinite() && outline.width.isFinite() && outline.height.isFinite())
        require(outline.width > 0f && outline.height > 0f)
        val radius = outline.topLeftCornerRadius
        require(radius.x.isFinite() && radius.x >= 0f && radius.x == radius.y)
        require(radius.x <= minOf(outline.width, outline.height) / 2f)
        require(outline.topRightCornerRadius == radius && outline.bottomRightCornerRadius == radius &&
            outline.bottomLeftCornerRadius == radius)
    }

    val corner get() = outline.topLeftCornerRadius.x

    /** Preserve the selector's existing inclusive circular boundary and Float operation order. */
    fun contains(point: Offset): Boolean {
        val bounds = bounds
        if (!bounds.contains(point)) return false
        val x = point.x.coerceIn(bounds.left + corner, bounds.right - corner)
        val y = point.y.coerceIn(bounds.top + corner, bounds.bottom - corner)
        val dx = point.x - x; val dy = point.y - y
        return dx * dx + dy * dy <= corner * corner
    }

    fun translated(offset: Offset): SelectorTrackContour {
        require(offset.x.isFinite() && offset.y.isFinite())
        return SelectorTrackContour(RoundRect(outline.left + offset.x, outline.top + offset.y,
            outline.right + offset.x, outline.bottom + offset.y, outline.topLeftCornerRadius,
            outline.topRightCornerRadius, outline.bottomRightCornerRadius, outline.bottomLeftCornerRadius))
    }

    companion object {
        fun uniform(bounds: Rect, corner: Float): SelectorTrackContour {
            require(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() &&
                bounds.bottom.isFinite() && corner.isFinite())
            require(bounds.width > 0f && bounds.height > 0f && corner >= 0f)
            val radius = corner.coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
            return SelectorTrackContour(RoundRect(bounds, CornerRadius(radius)))
        }
    }
}

/** Uniform adapters share one outline while optical execution remains on the scalar path. */
internal fun SelectorTrackLens.contour() = SelectorTrackContour.uniform(bounds, corner)
