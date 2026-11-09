package io.github.anastaci1a.localica.ui.spec

import kotlin.math.roundToInt

/** A presentation keeps this immutable policy for its full open/close lifetime. */
internal sealed interface GlassDialogHeight {
    data object Content : GlassDialogHeight
    data class ViewportFraction(val fraction: Float) : GlassDialogHeight {
        init { require(fraction.isFinite() && fraction > 0f && fraction <= 1f) }
    }
}

internal enum class PopupViewportInsets { None, SafeDrawingAndIme, SystemBarsAndCutout }

internal fun GlassDialogHeight.fixedViewportHeight(availableHeight: Int): Int? = when (this) {
    GlassDialogHeight.Content -> null
    is GlassDialogHeight.ViewportFraction -> (availableHeight.coerceAtLeast(0) * fraction).roundToInt()
}
