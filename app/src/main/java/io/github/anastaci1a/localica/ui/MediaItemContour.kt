package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Playback frost and selection glass have one footprint, independent of their material. */
internal val MediaSelectionGap = 16.dp

/** The one padding between an item's content (a cover's top edge, a text line's bottom) and its selection
 * outline, for every item style (#558): 1.75x the former 2 dp minimum inset. */
internal val MediaOutlinePadding = 3.5.dp

/** Rows without artwork (numbered and text-only sizes) share one modest radius. */
internal val ArtlessOutlineCorner = 10.dp

/** One outline for selection, the current-occurrence frost and held cards (#532), in the
 * item's own coordinates. Its corners are concentric with the artwork's corners. */
@Immutable internal data class MediaItemOutline(val bounds: Rect, val corner: Float) {
    val contour: RoundRect get() = RoundRect(bounds, CornerRadius(corner))
    fun translate(offset: Offset) = MediaItemOutline(bounds.translate(offset), corner)
    fun mirrored(width: Float) = MediaItemOutline(Rect(width - bounds.right, bounds.top, width - bounds.left, bounds.bottom), corner)
}

/** Measured by the item's own layout (including pinch endpoints) and read while drawing. */
@Stable internal class MediaItemOutlineHolder {
    var value by mutableStateOf<MediaItemOutline?>(null)
}

/**
 * The outline shares the artwork corners' arc centres: its inset is always [padding], and its radius is the
 * artwork radius plus that padding. Everything else in [content] stays inside the same padding.
 * The outline may extend past the item by at most [padding], never into a neighbour.
 */
internal fun mediaItemOutline(box: Rect, content: Rect, art: Rect?, artCorner: Float,
    padding: Float, artlessCorner: Float): MediaItemOutline {
    if (art == null || art.width <= 0f || art.height <= 0f)
        return MediaItemOutline(box, min(artlessCorner, box.minDimension / 2f).coerceAtLeast(0f))
    val inset = padding
    val limit = box.inflate(padding)
    val bounds = Rect(min(content.left, art.left) - inset, min(content.top, art.top) - inset,
        max(content.right, art.right) + inset, max(content.bottom, art.bottom) + inset).intersect(limit)
    return MediaItemOutline(bounds, min(artCorner + inset, bounds.minDimension / 2f).coerceAtLeast(0f))
}

/** Interpolates two endpoint outlines (pinch, landing) with the same progress as their parts. */
internal fun mediaItemOutlineLerp(from: MediaItemOutline, to: MediaItemOutline, progress: Float): MediaItemOutline {
    val p = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * p
    return MediaItemOutline(Rect(mix(from.bounds.left, to.bounds.left), mix(from.bounds.top, to.bounds.top),
        mix(from.bounds.right, to.bounds.right), mix(from.bounds.bottom, to.bounds.bottom)), mix(from.corner, to.corner))
}

/** A browse item's outline from its placed parts (left-to-right coordinates). The menu
 * contributes only its horizontal extent; its touch target is taller than what it draws. */
internal fun browsePartsOutline(width: Float, height: Float, art: Rect?, artCorner: Float, parts: List<Rect>,
    menu: Rect, density: Float): MediaItemOutline {
    val centre = menu.center.y
    val content = (parts + listOfNotNull(art) + Rect(menu.left, centre, menu.right, centre))
        .filter { it.width > 0f || it.height > 0f }
        .reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
        ?: Rect(0f, 0f, width, height)
    return mediaItemOutline(Rect(0f, 0f, width, height), content, art, artCorner,
        MediaOutlinePadding.value * density, ArtlessOutlineCorner.value * density)
}

/** A pinch endpoint's outline in its own coordinates. Its box is symmetric around the
 * content, as [BrowseItemMetrics] places it. */
internal fun BrowsePinchEndpoint.outline(density: Float): MediaItemOutline {
    fun rect(x: Int, y: Int, w: Int, h: Int) = Rect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    val parts = listOfNotNull(rect(titleX, titleY, title.size.width, title.size.height),
        information?.let { rect(infoX, infoY, it.size.width, it.size.height) })
    val art = if (artwork) rect(artX, artY, this.art, this.art) else null
    val menu = rect(menuX, menuY, menuWidth, menuHeight)
    val leading = minOf(titleX, if (artwork) artX else titleX).toFloat()
    val trailing = maxOf(menu.right, art?.right ?: 0f, parts.maxOf { it.right })
    return browsePartsOutline(trailing + leading, height.toFloat(), art, artCorner, parts, menu, density)
}

/** A fixed-padding row or tile: artwork at [art] inside [padding] (all in pixels). */
internal fun paddedItemOutline(size: Size, art: Rect?, artCorner: Float, padding: Rect, density: Float,
    direction: LayoutDirection = LayoutDirection.Ltr): MediaItemOutline {
    val box = Rect(Offset.Zero, size)
    val content = Rect(padding.left, padding.top, size.width - padding.right, size.height - padding.bottom)
    val outline = mediaItemOutline(box, content, art, artCorner, MediaOutlinePadding.value * density, ArtlessOutlineCorner.value * density)
    return if (direction == LayoutDirection.Rtl) outline.mirrored(size.width) else outline
}

/** Only callers without a measured outline fall back to their (visual) bounds. */
internal fun mediaItemBounds(measured: Size, visual: Size = measured,
    direction: LayoutDirection = LayoutDirection.Ltr): Rect = Rect(
    Offset(if (direction == LayoutDirection.Rtl) measured.width - visual.width else 0f, 0f), visual)

internal fun mediaItemCornerRadius(bounds: Rect, gap: Float): Float =
    minOf(gap * 1.5f, bounds.width / 2f, bounds.height / 2f).coerceAtLeast(0f)

internal fun mediaItemContour(bounds: Rect, gap: Float): RoundRect =
    RoundRect(bounds, CornerRadius(mediaItemCornerRadius(bounds, gap)))

/** Outline for a fixed layout: artwork of [art] size at ([artStart], [artTop], or vertically
 * centred when null) inside uniform [horizontal]/[vertical] padding. */
@androidx.compose.runtime.Composable
internal fun rememberPaddedItemOutline(art: androidx.compose.ui.unit.Dp?, artStart: androidx.compose.ui.unit.Dp,
    artTop: androidx.compose.ui.unit.Dp?, horizontal: androidx.compose.ui.unit.Dp, vertical: androidx.compose.ui.unit.Dp,
    artCorner: androidx.compose.ui.unit.Dp = 8.dp, centred: Boolean = false): (Size, LayoutDirection) -> MediaItemOutline {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    return androidx.compose.runtime.remember(density, art, artStart, artTop, horizontal, vertical, artCorner, centred) {
        { size, direction ->
            val artRect = art?.let {
                val side = it.value * density
                val left = if (centred) (size.width - side) / 2f else artStart.value * density
                val top = artTop?.let { t -> t.value * density } ?: (size.height - side) / 2f
                Rect(left, top, left + side, top + side)
            }
            paddedItemOutline(size, artRect, artCorner.value * density, Rect(horizontal.value * density,
                vertical.value * density, horizontal.value * density, vertical.value * density), density, direction)
        }
    }
}

/** A settled browse item's outline from [BrowseItemMetrics] placement (#532). [width] is the
 * item's full layout width; parts are mirrored for right-to-left layouts as placeRelative does. */
internal fun BrowseItemMetrics.outline(placement: BrowseItemPlacement, width: Int, titleWidth: Int, titleHeight: Int,
    infoWidth: Int, infoHeight: Int, artwork: Boolean, rtl: Boolean, density: Float): MediaItemOutline {
    fun rect(x: Int, y: Int, w: Int, h: Int) = Rect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    val measured = browsePartsOutline(width.toFloat(), placement.height.toFloat(),
        if (art > 0 && artwork) rect(placement.artX, placement.artY, art, art) else null, artCorner,
        listOf(rect(placement.titleX, placement.titleY, titleWidth, titleHeight), rect(placement.infoX, placement.infoY, infoWidth, infoHeight)),
        rect(placement.menuX, placement.menuY, menu, placement.menuHeight), density)
    return if (rtl) measured.mirrored(width.toFloat()) else measured
}
