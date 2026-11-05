package io.github.anastaci1a.localica.browse

import kotlin.math.roundToInt

/** The one gallery gap (dp): between neighbouring covers, and from the bottom of an item's
 * lowest text line to the next cover, at every gallery size (#561). It is the former 3-per-row gutter. */
internal const val GalleryGapDp = 8

/** Shared grid, artwork and lifted-card insets; dimensions are dp until a viewport is supplied. */
internal data class BrowseCellGeometry(val columns: Int) {
    init { require(columns > 0) }
    /** Two or more columns have neighbouring covers and share [GalleryGapDp]. One column
     * (and every list) keeps its 12 dp content inset, so its edge margins stay 16 dp. */
    val dense = columns >= 2
    val gridInset = if (dense) 12 else 0
    val cardInset = GalleryGapDp / 2
    fun cardVerticalInset(style: BrowseStyle): Float = cardInset *
        if (style.presentation == Presentation.List && style.listSize == ListSize.Smallest) .15f else 1f
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

    companion object {
        /** A horizontal album carousel is one dense gallery row (#573): each tile insets its cover by
         * [cardInset], tiles touch, and the row keeps [gridInset], so neighbouring covers share
         * [GalleryGapDp] and their outlines join like gallery neighbours. */
        val Carousel = BrowseCellGeometry(columns = 2)
    }
}
