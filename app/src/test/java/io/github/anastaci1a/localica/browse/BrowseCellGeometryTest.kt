package io.github.anastaci1a.localica.browse

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class BrowseCellGeometryTest {
    @Test fun denseArtworkRestoresUsableWidthWithoutMovingOuterEdges() {
        val expected = listOf(328, 148, 104, 76, 60)
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
    @Test fun denseGuttersAreBetweenCoversRatherThanRepeatedInsideEveryTile() {
        for (columns in 3..5) {
            val geometry = BrowseCellGeometry(columns)
            for (column in 0 until columns - 1) {
                val end = geometry.cellLeft(360, 1f, column) + geometry.cellWidth(360, 1f, column) - geometry.cardInset
                val next = geometry.cellLeft(360, 1f, column + 1) + geometry.cardInset
                assertEquals(8, next - end)
            }
        }
    }
    @Test fun smallestReducesOnlyBetweenRowPaddingToSixtyPercent() {
        val geometry = BrowseCellGeometry(1)
        val smallest = BrowseStyle(listSize = ListSize.Smallest)
        assertEquals(2.4f, geometry.cardVerticalInset(smallest), .00001f)
        assertEquals(4, geometry.cardInset)
        for (size in ListSize.entries.filterNot { it == ListSize.Smallest })
            assertEquals(4f, geometry.cardVerticalInset(BrowseStyle(listSize = size)), 0f)
        assertEquals(4f, geometry.cardVerticalInset(smallest.copy(presentation = Presentation.Standard)), 0f)
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) {
            val smallestGap = (geometry.cardVerticalInset(smallest) * density).roundToInt() * 2
            assertEquals(2 * geometry.cardInset * .6f * density, smallestGap.toFloat(), 1f)
        }
    }

}
