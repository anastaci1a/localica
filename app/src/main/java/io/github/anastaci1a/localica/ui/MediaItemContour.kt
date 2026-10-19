package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Playback frost and selection glass have one footprint, independent of their material. */
internal val MediaSelectionGap = 16.dp

internal fun mediaItemBounds(measured: Size, visual: Size = measured,
    direction: LayoutDirection = LayoutDirection.Ltr): Rect = Rect(
    Offset(if (direction == LayoutDirection.Rtl) measured.width - visual.width else 0f, 0f), visual)

internal fun mediaItemCornerRadius(bounds: Rect, gap: Float): Float =
    minOf(gap * 1.5f, bounds.width / 2f, bounds.height / 2f).coerceAtLeast(0f)

internal fun mediaItemContour(bounds: Rect, gap: Float): RoundRect =
    RoundRect(bounds, CornerRadius(mediaItemCornerRadius(bounds, gap)))
