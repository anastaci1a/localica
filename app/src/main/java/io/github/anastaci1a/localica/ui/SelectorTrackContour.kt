package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect

/** One physical outline for future selector adapters; uniform contours retain today's geometry. */
internal data class SelectorTrackContour private constructor(val outline: RoundRect) {
    val bounds get() = Rect(outline.left, outline.top, outline.right, outline.bottom)

    init {
        require(listOf(outline.left, outline.top, outline.right, outline.bottom,
            outline.width, outline.height).all { it.isFinite() })
        require(outline.width > 0f && outline.height > 0f)
        val radius = outline.topLeftCornerRadius
        require(radius.x.isFinite() && radius.x >= 0f && radius.x == radius.y)
        require(radius.x <= minOf(outline.width, outline.height) / 2f)
        require(listOf(outline.topRightCornerRadius, outline.bottomRightCornerRadius,
            outline.bottomLeftCornerRadius).all { it == radius })
    }

    fun contains(point: Offset): Boolean = point.x.isFinite() && point.y.isFinite() && outline.contains(point)

    fun translated(offset: Offset): SelectorTrackContour {
        require(offset.x.isFinite() && offset.y.isFinite())
        return SelectorTrackContour(RoundRect(outline.left + offset.x, outline.top + offset.y,
            outline.right + offset.x, outline.bottom + offset.y, outline.topLeftCornerRadius,
            outline.topRightCornerRadius, outline.bottomRightCornerRadius, outline.bottomLeftCornerRadius))
    }

    companion object {
        fun uniform(bounds: Rect, corner: Float): SelectorTrackContour {
            require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom, corner).all { it.isFinite() })
            require(bounds.width > 0f && bounds.height > 0f && corner >= 0f)
            val radius = corner.coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
            return SelectorTrackContour(RoundRect(bounds, CornerRadius(radius)))
        }
    }
}

/** No renderer consumes this adapter until every optical path supports the same outline. */
internal fun SelectorTrackLens.contour() = SelectorTrackContour.uniform(bounds, corner)
