package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.browse.Presentation
import kotlin.math.roundToInt

/** One sizing contract for ordinary rows and both fixed pinch endpoints. */
internal class BrowseItemMetrics(val style: BrowseStyle, columns: Int, val width: Int,
    private val density: Float, numbered: Boolean) {
    val gallery = style.presentation != Presentation.List
    val trailing = !gallery && style.listSize.trailingInformation
    val inset = px(BrowseCellGeometry(columns).contentInset)
    val verticalInset = when { gallery -> px(8); trailing -> 0; else -> px(2) }
    private val listGeometry = BrowseCellGeometry(1)
    private val viewport = width + 2 * (px(listGeometry.cardInset) + px(listGeometry.contentInset))
    private val normalArt = BrowseCellGeometry(5).artworkWidth(viewport, density)
    private val normalGap = maxOf(normalArt, px(48)) - normalArt + 2 * (px(listGeometry.cardInset) + px(2))
    val titleSize = style.browseTitleSize(columns)
    val titleLine = style.browseTitleLine(columns)
    val lines = if (gallery) 2 else 1
    val art = when {
        gallery -> width
        numbered || style.listSize == ListSize.Tracklist -> px(28).coerceAtMost(width)
        style.listSize in setOf(ListSize.Small, ListSize.Smallest) -> 0
        style.listSize == ListSize.Compact -> {
            // Preserve the 56 dp inner row and 64 dp pitch. Match Normal's gap/art
            // ratio until the unchanged outer card padding becomes the limit.
            val pitch = px(56) + 2 * px(listGeometry.cardInset)
            (pitch * normalArt.toFloat() / (normalArt + normalGap).coerceAtLeast(1))
                .roundToInt().coerceAtMost(px(56)).coerceAtMost(width)
        }
        else -> {
            BrowseCellGeometry(if (style.listSize == ListSize.Normal) 5 else 3)
                .artworkWidth(viewport, density).coerceAtMost(width)
        }
    }
    val artCorner = if (!gallery && !numbered && style.listSize == ListSize.Compact)
        8 * density * art / normalArt.coerceAtLeast(1) else 8 * density
    val menu = if (gallery) 0 else px(48)
    val gap = if (gallery || art == 0) 0 else px(12)
    private val available = (if (gallery) width else width - art - gap - menu).coerceAtLeast(0)
    val informationLimit = if (trailing) available * 2 / 5 else available
    fun titleWidth(infoWidth: Int) =
        (available - if (trailing) infoWidth + (if (infoWidth > 0) px(8) else 0) else 0).coerceAtLeast(0)

    /** Placeholder widths stay unchanged when the real (possibly shorter) label arrives. */
    fun loadingInformationWidth(showInformation: Boolean): Int = when {
        !showInformation -> 0
        trailing -> informationLimit
        else -> (titleWidth(0) * .65f).roundToInt()
    }

    fun place(titleHeight: Int, infoWidth: Int, infoHeight: Int): BrowseItemPlacement {
        val textHeight = if (trailing) maxOf(titleHeight, infoHeight) else titleHeight + infoHeight
        val minimum = px(when (style.listSize) { ListSize.Compact -> 56; ListSize.Small -> 48; ListSize.Smallest -> 40; else -> 48 })
        val row = if (gallery) art + textHeight else maxOf(art, textHeight, minimum)
        val titleY = if (gallery) art else (row - textHeight) / 2
        val titleX = inset + if (gallery) 0 else art + gap
        val menuHeight = minOf(row, px(48))
        return BrowseItemPlacement(inset, verticalInset + if (gallery) 0 else (row - art) / 2,
            titleX, verticalInset + titleY,
            if (trailing) inset + width - menu - infoWidth else titleX,
            verticalInset + if (trailing) (row - infoHeight) / 2 else titleY + titleHeight,
            inset + width - menu, verticalInset + if (gallery) art else (row - menuHeight) / 2,
            menuHeight, row + verticalInset * 2)
    }

    private fun px(dp: Int) = (dp * density).roundToInt()
}

internal data class BrowseItemPlacement(val artX: Int, val artY: Int, val titleX: Int, val titleY: Int,
    val infoX: Int, val infoY: Int, val menuX: Int, val menuY: Int, val menuHeight: Int, val height: Int)

internal fun BrowseStyle.browseTitleSize(columns: Int) = when {
    presentation != Presentation.List -> if (columns >= 3) 12 else 16
    listSize.trailingInformation -> 14
    listSize == ListSize.Large -> 18
    else -> 16
}

internal fun BrowseStyle.browseTitleLine(columns: Int) = when {
    presentation != Presentation.List -> if (columns >= 3) 16 else 24
    listSize.trailingInformation -> 20
    listSize == ListSize.Large -> 26
    else -> 24
}
