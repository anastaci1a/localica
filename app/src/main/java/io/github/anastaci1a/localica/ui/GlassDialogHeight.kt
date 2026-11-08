package io.github.anastaci1a.localica.ui

import kotlin.math.roundToInt

/** Fixed for a presentation. Content keeps its easing; the Sheet host owns a viewport fraction once. */
internal sealed interface GlassDialogHeight {
    data object Content : GlassDialogHeight
    data class ViewportFraction(val fraction: Float) : GlassDialogHeight {
        init { require(fraction.isFinite() && fraction > 0f && fraction <= 1f) }
    }
}

internal fun GlassDialogHeight.fixedViewportHeight(availableHeight: Int): Int? = when (this) {
    GlassDialogHeight.Content -> null
    is GlassDialogHeight.ViewportFraction -> (availableHeight.coerceAtLeast(0) * fraction).roundToInt()
}

internal fun GlassDialogHeight.expandedHeight(measuredHeight: Int, easedHeight: Float): Float = when (this) {
    GlassDialogHeight.Content -> easedHeight.takeIf { it >= 0f } ?: measuredHeight.toFloat()
    is GlassDialogHeight.ViewportFraction -> measuredHeight.toFloat()
}
