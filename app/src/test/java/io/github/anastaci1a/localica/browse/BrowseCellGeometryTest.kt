package io.github.anastaci1a.localica.browse

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class BrowseCellGeometryTest {
    @Test fun denseArtworkRestoresUsableWidthWithoutMovingOuterEdges() {
        val expected = listOf(328, 160, 104, 76, 60)
        for (columns in 1..5) {
            val geometry = BrowseCellGeometry(columns)
            assertEquals(expected[columns - 1], geometry.contentWidth(geometry.cellWidth(360, 1f), 1f))
            val first = geometry.cellLeft(360, 1f, 0) + geometry.cardInset + geometry.contentInset
            val last = geometry.cellLeft(360, 1f, columns - 1) + geometry.cellWidth(360, 1f, columns - 1) - geometry.cardInset - geometry.contentInset
            assertEquals(16, first)
            assertEquals(344, last)
        }
    }
    @Test fun cellsAndInsetsPartitionOddPixelViewportsExactly() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) for (columns in 1..5) for (viewport in listOf(359, 721, 1081)) {
            val geometry = BrowseCellGeometry(columns)
            val inset = (geometry.gridInset * density).roundToInt()
            var edge = inset
            for (column in 0 until columns) {
                assertEquals(edge, geometry.cellLeft(viewport, density, column))
                val width = geometry.cellWidth(viewport, density, column)
                assertTrue(geometry.contentWidth(width, density) >= 0)
                edge += width
            }
            assertEquals(viewport - inset, edge)
        }
    }
    @Test fun galleryGuttersAreBetweenCoversRatherThanRepeatedInsideEveryTile() {
        for (columns in 2..5) {
            val geometry = BrowseCellGeometry(columns)
            for (column in 0 until columns - 1) {
                val end = geometry.cellLeft(360, 1f, column) + geometry.cellWidth(360, 1f, column) - geometry.cardInset
                val next = geometry.cellLeft(360, 1f, column + 1) + geometry.cardInset
                assertEquals(8, next - end)
            }
        }
    }
    @Test fun carouselCoversShareTheGalleryGapAndEdgeAtEveryDensity() {
        // #573: touching carousel tiles each inset their cover by the gallery card inset.
        val carousel = BrowseCellGeometry.Carousel
        for (density in listOf(.75f, 1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) for (art in listOf(112, 160)) {
            val tile = ((art + 2 * carousel.cardInset) * density).roundToInt()
            val pad = (carousel.cardInset * density).roundToInt()
            val row = (carousel.gridInset * density).roundToInt()
            fun coverLeft(k: Int) = row + k * tile + pad
            fun coverRight(k: Int) = row + k * tile + tile - pad
            val gallery = BrowseCellGeometry(3)
            val viewport = (360 * density).roundToInt()
            val galleryGap = gallery.cellLeft(viewport, density, 1) + (gallery.cardInset * density).roundToInt() -
                (gallery.cellLeft(viewport, density, 0) + gallery.cellWidth(viewport, density, 0) - (gallery.cardInset * density).roundToInt())
            for (k in 0..3) assertEquals("density $density art $art", galleryGap, coverLeft(k + 1) - coverRight(k))
            assertEquals(GalleryGapDp * density, galleryGap.toFloat(), 1f)
            // The first cover sits where a dense gallery's first cover does.
            assertEquals(gallery.cellLeft(viewport, density, 0) + (gallery.cardInset * density).roundToInt(), coverLeft(0))
        }
    }

    @Test fun smallestUsesFifteenPercentOfOrdinaryOuterPadding() {
        val geometry = BrowseCellGeometry(1)
        val smallest = BrowseStyle(listSize = ListSize.Smallest)
        assertEquals(.6f, geometry.cardVerticalInset(smallest), .00001f)
        assertEquals(4, geometry.cardInset)
        for (size in ListSize.entries.filterNot { it == ListSize.Smallest })
            assertEquals(4f, geometry.cardVerticalInset(BrowseStyle(listSize = size)), 0f)
        assertEquals(4f, geometry.cardVerticalInset(smallest.copy(presentation = Presentation.Standard)), 0f)
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) {
            val smallestGap = (geometry.cardVerticalInset(smallest) * density).roundToInt() * 2
            assertEquals(2 * geometry.cardInset * .15f * density, smallestGap.toFloat(), 1f)
        }
    }

}
