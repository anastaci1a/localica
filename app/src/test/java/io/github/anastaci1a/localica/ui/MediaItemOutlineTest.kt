package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

/** #532: one outline whose corners are concentric with the artwork and always contain it. */
class MediaItemOutlineTest {
    private class Item(val outline: MediaItemOutline, val art: Rect?, val artCorner: Float, val box: Rect, val density: Float)

    private fun item(style: BrowseStyle, columns: Int, viewport: Int, density: Float, rtl: Boolean,
        titleHeight: Int = (24 * density).roundToInt(), infoHeight: Int = (16 * density).roundToInt()): Item {
        val geometry = BrowseCellGeometry(columns)
        val cell = geometry.cellWidth((viewport * density).roundToInt(), density)
        val full = geometry.cardWidth(cell, density)
        val metrics = BrowseItemMetrics(style, columns, geometry.contentWidth(cell, density), density, false)
        val infoWidth = if (metrics.trailing) (40 * density).roundToInt() else metrics.titleWidth(0)
        val placement = metrics.place(titleHeight, infoWidth, infoHeight)
        val outline = metrics.outline(placement, full, metrics.titleWidth(infoWidth), titleHeight, infoWidth, infoHeight,
            true, rtl, density)
        val art = if (metrics.art == 0) null else Rect(placement.artX.toFloat(), placement.artY.toFloat(),
            (placement.artX + metrics.art).toFloat(), (placement.artY + metrics.art).toFloat())
            .let { if (rtl) Rect(full - it.right, it.top, full - it.left, it.bottom) else it }
        return Item(outline, art, metrics.artCorner, Rect(0f, 0f, full.toFloat(), placement.height.toFloat()), density)
    }

    private fun styles() = ListSize.entries.filter { it != ListSize.Tracklist }.map { BrowseStyle(listSize = it) to 1 } +
        (1..5).map { BrowseStyle(presentation = Presentation.Standard, itemsPerRow = it) to it }

    @Test fun artworkCornersAreConcentricAndContainedInEverySizeAndDirection() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f)) for (viewport in listOf(320, 360, 411, 600)) for (rtl in listOf(false, true)) {
            for ((style, columns) in styles()) {
                val label = "${style.presentation}/${style.listSize}/$columns density=$density viewport=$viewport rtl=$rtl"
                val item = item(style, columns, viewport, density, rtl)
                val outline = item.outline
                val art = item.art ?: continue
                val minimum = MediaOutlineMinimumInset.value * density
                // Containment with a visible inset on every side.
                assertTrue("Artwork leaves the outline: $label $art ${outline.bounds}",
                    art.left - outline.bounds.left >= minimum - .01f && outline.bounds.right - art.right >= minimum - .01f &&
                        art.top - outline.bounds.top >= minimum - .01f && outline.bounds.bottom - art.bottom >= minimum - .01f)
                // Concentric: the artwork's leading-top corner arc centre is the outline's.
                val inset = art.top - outline.bounds.top
                val leadingInset = if (rtl) outline.bounds.right - art.right else art.left - outline.bounds.left
                assertEquals("Equal inset around the leading corner: $label", inset, leadingInset, .01f)
                assertEquals("Radius is artwork radius plus inset: $label", item.artCorner + inset, outline.corner, .01f)
                val artCentre = if (rtl) Offset(art.right - item.artCorner, art.top + item.artCorner)
                    else Offset(art.left + item.artCorner, art.top + item.artCorner)
                val outlineCentre = if (rtl) Offset(outline.bounds.right - outline.corner, outline.bounds.top + outline.corner)
                    else Offset(outline.bounds.left + outline.corner, outline.bounds.top + outline.corner)
                assertEquals("Shared arc centre: $label", 0f, (artCentre - outlineCentre).getDistance(), .02f)
                // Every point of the artwork's corner arc keeps the same gap to the outline arc.
                for (step in 0..8) {
                    val angle = Math.PI / 2 * step / 8
                    val direction = Offset((if (rtl) 1 else -1) * kotlin.math.cos(angle).toFloat(), -kotlin.math.sin(angle).toFloat())
                    val gap = outline.corner - item.artCorner
                    val artPoint = artCentre + direction * item.artCorner
                    val outlinePoint = outlineCentre + direction * outline.corner
                    assertEquals("Constant corner gap: $label angle=$step", gap, (outlinePoint - artPoint).getDistance(), .02f)
                }
                // The outline never reaches into a neighbouring card.
                assertTrue("Outline leaves the card by at most the minimum inset: $label",
                    outline.bounds.left >= item.box.left - minimum - .01f && outline.bounds.right <= item.box.right + minimum + .01f &&
                        outline.bounds.top >= item.box.top - minimum - .01f && outline.bounds.bottom <= item.box.bottom + minimum + .01f)
            }
        }
    }

    @Test fun compactListAndDenseGalleryNoLongerPokeOutOfTheOutline() {
        // The reported cases: List-Compact rows and galleries with three or more per row.
        for ((style, columns) in listOf(BrowseStyle(listSize = ListSize.Compact) to 1,
            BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3) to 3,
            BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5) to 5)) {
            val item = item(style, columns, 411, 2.625f, false)
            val art = requireNotNull(item.art)
            val contour = item.outline.contour
            // Sample the artwork's own rounded silhouette densely.
            val r = item.artCorner
            for (step in 0..32) {
                val angle = Math.PI / 2 * step / 32
                val point = Offset(art.left + r - r * kotlin.math.cos(angle).toFloat(), art.top + r - r * kotlin.math.sin(angle).toFloat())
                assertTrue("$style $columns: artwork corner point $point outside ${item.outline}", inside(contour, point))
            }
        }
    }

    @Test fun artlessRowsUseTheWholeCardAndTheSharedRadius() {
        for (size in listOf(ListSize.Small, ListSize.Smallest)) {
            val item = item(BrowseStyle(listSize = size), 1, 360, 2f, false)
            assertNull(item.art)
            assertEquals(item.box, item.outline.bounds)
            assertEquals(minOf(ArtlessOutlineCorner.value * 2f, item.box.height / 2f), item.outline.corner, .01f)
        }
    }

    @Test fun pinchInterpolationKeepsEndpointsAndMovesContinuously() {
        val from = MediaItemOutline(Rect(10f, 0f, 300f, 60f), 10f)
        val to = MediaItemOutline(Rect(-2f, 6f, 120f, 180f), 16f)
        assertEquals(from, mediaItemOutlineLerp(from, to, 0f))
        assertEquals(to, mediaItemOutlineLerp(from, to, 1f))
        var previous = from
        for (step in 1..20) {
            val next = mediaItemOutlineLerp(from, to, step / 20f)
            assertTrue((next.bounds.topLeft - previous.bounds.topLeft).getDistance() < 2f)
            assertTrue(kotlin.math.abs(next.corner - previous.corner) < .5f)
            previous = next
        }
    }

    @Test fun paddedSurfacesShareTheRule() {
        // Artist carousel: 112 dp artwork centred in 12 dp padding gives a 20 dp radius on the card.
        val density = 2f
        val carousel = paddedItemOutline(Size(136f * density, 170f * density),
            Rect(12f * density, 12f * density, 124f * density, 124f * density), 8f * density,
            Rect(12f * density, 12f * density, 12f * density, 12f * density), density)
        assertEquals(Rect(0f, 0f, 136f * density, 170f * density), carousel.bounds)
        assertEquals(20f * density, carousel.corner, .01f)
        // Playlist chooser row: 44 dp artwork, 4 dp/8 dp padding gives a 4 dp concentric inset.
        val row = paddedItemOutline(Size(280f * density, 60f * density),
            Rect(4f * density, 8f * density, 48f * density, 52f * density), 8f * density,
            Rect(4f * density, 8f * density, 4f * density, 8f * density), density)
        assertEquals(Rect(0f, 4f * density, 280f * density, 56f * density), row.bounds)
        assertEquals(12f * density, row.corner, .01f)
    }

    private fun inside(contour: androidx.compose.ui.geometry.RoundRect, point: Offset): Boolean {
        val r = contour.topLeftCornerRadius.x
        val x = point.x.coerceIn(contour.left + r, contour.right - r)
        val y = point.y.coerceIn(contour.top + r, contour.bottom - r)
        return point.x >= contour.left - .01f && point.x <= contour.right + .01f && point.y >= contour.top - .01f &&
            point.y <= contour.bottom + .01f && hypot(point.x - x, point.y - y) <= r + .01f
    }
}
