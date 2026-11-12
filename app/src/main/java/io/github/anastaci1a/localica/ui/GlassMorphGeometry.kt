package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** A popup's resting window margin in pixels, exactly v0.34.1's for every motion kind (#854 review): the spring overshoot exists
 * only in flight and may pass this margin at its peak; it never moves or shrinks a resting menu or dialog. Pages fill the window. */
internal fun Density.popupMarginPx(page: Boolean): Float = if (page) 0f else StockUiSpec.space.getValue(Space.Base).toPx()

/** Corners stay between their endpoints while the owning edges retain spring overshoot. */
internal fun popupCornerRadius(home: Dp, popup: Dp, geometry: Float): Dp {
    val g = geometry.coerceIn(0f, 1f)
    return home * (1f - g) + popup * g
}

/** Quiet controls finish all shape changes together before their temporary lens fades. */
internal fun glassMorphGeometryProgress(progress: Float, quiet: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
}
