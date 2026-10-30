package io.github.anastaci1a.localica.ui

/** Cross-axis geometry view used only by JVM contour and placement fixtures. */
internal data class SelectorCrossFrame(val shell: Float, val lens: Float, val corner: Float = 0f, val outerCorner: Float = 0f) {
    init {
        require(shell.isFinite() && lens.isFinite() && shell > 0f && lens > 0f && lens <= shell)
        require(corner.isFinite() && outerCorner.isFinite() && corner >= 0f && outerCorner >= 0f)
    }
}

/** Project the cross-axis view for geometry checks. Production returns use the full frame. */
internal fun SelectorTrackLens.withCrossFrame(frame: SelectorCrossFrame, hostCross: Float,
    vertical: Boolean): SelectorTrackLens {
    val extent = frame.lens
    val start = (hostCross - extent) / 2f
    val adjusted = if (vertical) androidx.compose.ui.geometry.Rect(start, bounds.top, start + extent, bounds.bottom)
        else androidx.compose.ui.geometry.Rect(bounds.left, start, bounds.right, start + extent)
    return copy(bounds = adjusted, crossExtent = extent, outerCorner = frame.outerCorner,
        corner = frame.corner.coerceIn(0f, minOf(adjusted.width, adjusted.height) / 2f))
}

// Retain the calculated extent: subtracting translated Rect edges can add one Float ULP.
internal fun SelectorTrackLens.crossFrame(geometry: SelectorTrackGeometry) =
    SelectorCrossFrame(geometry.crossExtent, crossExtent, corner, outerCorner)
