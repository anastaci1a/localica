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

    @Test fun smallMatchesArchivedDev53SmallestInnerHeight() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) {
            val small = metrics(ListSize.Small, density = density)
            assertEquals((40 * density).roundToInt(), small.place((20 * density).roundToInt(), 0,
                (16 * density).roundToInt()).height)
        }
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
        val geometry = BrowseCellGeometry(1)
        assertEquals(.6f, geometry.cardVerticalInset(BrowseStyle(listSize = ListSize.Smallest)), .00001f)
        for (size in ListSize.entries.filter { it != ListSize.Smallest })
            assertEquals(4f, geometry.cardVerticalInset(BrowseStyle(listSize = size)), 0f)
        assertEquals(4f, geometry.cardVerticalInset(BrowseStyle(presentation = Presentation.Standard, listSize = ListSize.Smallest)), 0f)
        for ((size, height) in listOf(ListSize.Compact to 56, ListSize.Small to 40, ListSize.Smallest to 29)) {
            val m = metrics(size)
            assertEquals(height, m.place(20, 0, 16).height)
            assertTrue(m.place(80, 0, 72).height >= 80)
            val numbered = metrics(size, numbered = true)
            assertEquals(28, numbered.art)
            assertEquals(height, numbered.place(20, 0, 16).height)
            if (size != ListSize.Compact) assertEquals(0, m.art)
        }
    }

    @Test fun smallestHalvesFormerTitleGapAcrossPixelDensitiesAndFontSizes() {
        for (density in listOf(1f, 1.5f, 1.75f, 2.625f, 3f)) {
            for (scale in listOf(1f, 1.3f, 2f)) for (numbered in listOf(false, true)) {
                val title = (20 * density * scale).roundToInt()
                val info = (16 * density * scale).roundToInt()
                val m = metrics(ListSize.Smallest, density = density, numbered = numbered)
                val row = m.place(title, 0, info)
                val formerHeight = maxOf((40 * density).roundToInt(), title, info, m.art)
                val formerGap = formerHeight + 2 * (1.2f * density).roundToInt() - title
                val gap = row.height + 2 * (.6f * density).roundToInt() - title
                assertEquals("$density/$scale numbered=$numbered", formerGap / 2f, gap.toFloat(), 1f)
                assertTrue(row.height >= maxOf(title, info, m.art))
            }
        }
    }

    @Test fun gallerySizingAndPaddingRemainUnchanged() {
        for (columns in 1..5) {
            val geometry = BrowseCellGeometry(columns)
            val width = geometry.artworkWidth(360, 1f)
            val m = BrowseItemMetrics(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), columns, width, 1f, false)
            assertEquals(width, m.art)
            assertEquals(8, m.verticalInset)
            assertEquals(width + 24 + 16 + 8, m.place(24, 0, 16).height)
            assertEquals(8f, m.artCorner, 0f)
        }
    }

    @Test fun galleryTextBottomToNextCoverGapIsHalvedFromSixteenDp() {
        for (density in listOf(1f, 2f, 2.625f, 3f)) for (columns in 1..5) {
            val width = BrowseCellGeometry(columns).artworkWidth((360 * density).roundToInt(), density)
            val m = BrowseItemMetrics(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), columns, width, density, false)
            val title = (24 * density).roundToInt(); val info = (16 * density).roundToInt()
            val cell = m.place(title, 0, info)
            // The next cell's cover starts at its own top inset, directly after this cell's height.
            val gap = cell.height + cell.artY - (cell.infoY + info)
            assertEquals("old gap was 16 dp", 8f * density, gap.toFloat(), 1f)
            assertEquals("first row keeps its top inset", (8 * density).roundToInt(), cell.artY)
            val noInfo = m.place(title, 0, 0)
            assertEquals(gap, noInfo.height + noInfo.artY - (noInfo.titleY + title))
        }
    }
    @Test fun tracklistPreservesNumberedHybridWhileNormalRestoresCoverSubtitleLayout() {
        val numbered = metrics(ListSize.Tracklist)
        val normal = metrics(ListSize.Normal)
        assertEquals(28, numbered.art)
        assertFalse(numbered.trailing)
        assertEquals(16, numbered.titleSize)
        assertEquals(52, numbered.place(24, 40, 16).height)
        assertTrue(normal.art > numbered.art)
        assertEquals(64, normal.place(24, 40, 16).height)
        val normalPlacement = normal.place(24, 40, 16)
        assertEquals(normalPlacement.titleY + 24, normalPlacement.infoY)
        assertEquals(numbered.art, metrics(ListSize.Tracklist, numbered = true).art)
    }

}
