package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class BrowseItemMetricsTest {
    private fun metrics(size: ListSize, viewport: Int = 360, density: Float = 1f, numbered: Boolean = false): BrowseItemMetrics {
        val geometry = BrowseCellGeometry(1)
        return BrowseItemMetrics(BrowseStyle(listSize = size), 1,
            geometry.contentWidth((viewport * density).roundToInt(), density), density, numbered)
    }

    @Test fun normalAndLargeHalveInterCoverGapWithoutChangingCoverOrHorizontalGeometry() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) for (viewport in listOf(320, 360, 411, 600)) {
            for ((size, columns) in listOf(ListSize.Normal to 5, ListSize.Large to 3)) {
                val m = metrics(size, viewport, density)
                assertEquals(BrowseCellGeometry(columns).artworkWidth((viewport * density).roundToInt(), density), m.art)
                val placement = m.place((24 * density).roundToInt(), 0, (16 * density).roundToInt())
                val pitch = placement.height + 2 * (4 * density).roundToInt()
                assertEquals("Total gap halves, allowing independent pixel rounding", 12 * density, (pitch - m.art).toFloat(), 2f)
                assertEquals((12 * density).roundToInt(), m.inset)
                assertEquals((12 * density).roundToInt(), m.gap)
                assertEquals(8 * density, m.artCorner, 0f)
            }
        }
    }

    @Test fun compactPreservesPitchAndTypographyWithProportionalArtAndCorners() {
        val reference = metrics(ListSize.Compact)
        assertEquals(53, reference.art)
        assertEquals(8f * 53 / 60, reference.artCorner, .00001f)
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) for (viewport in listOf(240, 320, 360, 411, 600)) {
            val normal = metrics(ListSize.Normal, viewport, density)
            val compact = metrics(ListSize.Compact, viewport, density)
            val row = compact.place((20 * density).roundToInt(), 0, (16 * density).roundToInt())
            assertEquals((56 * density).roundToInt(), row.height)
            assertEquals(14, compact.titleSize)
            assertEquals(20, compact.titleLine)
            assertTrue(compact.art > (32 * density).roundToInt())
            assertTrue(compact.art <= row.height)
            val pitch = row.height + 2 * (4 * density).roundToInt()
            val normalGap = normal.place((24 * density).roundToInt(), 0, (16 * density).roundToInt()).height +
                2 * (4 * density).roundToInt() - normal.art
            val expected = (pitch * normal.art.toFloat() / (normal.art + normalGap)).roundToInt().coerceAtMost(row.height)
            assertEquals(expected, compact.art)
            assertEquals(normal.artCorner / normal.art, compact.artCorner / compact.art, .00001f)
        }
    }

    @Test fun denseTextOnlyAndNumberedRowsRetainAccessiblePitchAndGrowForLargeText() {
        for ((size, height) in listOf(ListSize.Compact to 56, ListSize.Small to 48, ListSize.Smallest to 40)) {
            val m = metrics(size)
            assertEquals(height, m.place(20, 0, 16).height)
            assertTrue(m.place(80, 0, 72).height >= 80)
            val numbered = metrics(size, numbered = true)
            assertEquals(28, numbered.art)
            assertEquals(height, numbered.place(20, 0, 16).height)
            if (size != ListSize.Compact) assertEquals(0, m.art)
        }
    }

    @Test fun gallerySizingAndPaddingRemainUnchanged() {
        for (columns in 1..5) {
            val geometry = BrowseCellGeometry(columns)
            val width = geometry.artworkWidth(360, 1f)
            val m = BrowseItemMetrics(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), columns, width, 1f, false)
            assertEquals(width, m.art)
            assertEquals(8, m.verticalInset)
            assertEquals(width + 24 + 16 + 16, m.place(24, 0, 16).height)
            assertEquals(8f, m.artCorner, 0f)
        }
    }
}
