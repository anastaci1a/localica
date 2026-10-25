package io.github.anastaci1a.localica.browse

import kotlin.math.roundToInt

/** Shared grid, artwork and lifted-card insets; dimensions are dp until a viewport is supplied. */
internal data class BrowseCellGeometry(val columns: Int) {
    init { require(columns > 0) }
    val dense = columns >= 3
    val gridInset = if (dense) 12 else 0
    val cardInset = 4
    fun cardVerticalInset(style: BrowseStyle): Float = cardInset *
        if (style.presentation == Presentation.List && style.listSize == ListSize.Smallest) .6f else 1f
    val contentInset = if (dense) 0 else 12

    fun cellWidth(viewport: Int, density: Float, column: Int = 0): Int {
        val available = (viewport - 2 * (gridInset * density).roundToInt()).coerceAtLeast(0)
        return available / columns + if (column < available % columns) 1 else 0
    }
    fun cellLeft(viewport: Int, density: Float, column: Int): Int {
        val available = (viewport - 2 * (gridInset * density).roundToInt()).coerceAtLeast(0)
        return (gridInset * density).roundToInt() + column * (available / columns) + minOf(column, available % columns)
    }
    fun artworkWidth(viewport: Int, density: Float, column: Int = 0): Int =
        contentWidth(cellWidth(viewport, density, column), density)
    fun cardWidth(cellWidth: Int, density: Float): Int =
        (cellWidth - 2 * (cardInset * density).roundToInt()).coerceAtLeast(0)
    fun contentWidth(cellWidth: Int, density: Float): Int =
        (cardWidth(cellWidth, density) - 2 * (contentInset * density).roundToInt()).coerceAtLeast(0)
}
