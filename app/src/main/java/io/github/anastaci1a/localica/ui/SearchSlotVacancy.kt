package io.github.anastaci1a.localica.ui

/** Keep the first row's content edge at the departing pill's visible top.
 * Available space excludes an already-scrolled part of the slot. Existing row
 * padding stays owned by the row; a short/consumed slot never grows or goes negative.
 */
internal fun searchSlotVacancy(available: Float, pillTopInset: Float, rowTopInset: Float): Float {
    require(available.isFinite() && available >= 0f)
    require(pillTopInset.isFinite() && pillTopInset >= 0f)
    require(rowTopInset.isFinite() && rowTopInset >= 0f)
    return (available - (pillTopInset - rowTopInset).coerceAtLeast(0f)).coerceIn(0f, available)
}
