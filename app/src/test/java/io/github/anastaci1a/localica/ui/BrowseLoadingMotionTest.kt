package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test

class BrowseLoadingMotionTest {
    @Test fun waveUsesPhysicalDistanceInsteadOfItemIndexOrGalleryDensity() {
        assertEquals(0f, BrowseLoadingMotion.delayMillis(0f, 0f), 0f)
        assertEquals(1000f, BrowseLoadingMotion.delayMillis(1800f, 0f), .001f)
        assertEquals(BrowseLoadingMotion.delayMillis(300f, 400f), BrowseLoadingMotion.delayMillis(500f, 0f), .001f)
        assertEquals(BrowseLoadingMotion.delayMillis(400f, 300f), BrowseLoadingMotion.delayMillis(300f, 400f), 0f)
        assertEquals(0f, BrowseLoadingMotion.delayMillis(-20f, -100f), 0f)
    }

    @Test fun revealAndPlaceholderHaveComplementarySmoothEndpoints() {
        assertEquals(0f, BrowseLoadingMotion.fade(0f, 100f), 0f)
        assertEquals(0f, BrowseLoadingMotion.fade(100f, 100f), 0f)
        assertEquals(.5f, BrowseLoadingMotion.fade(250f, 100f), .0001f)
        assertEquals(1f, BrowseLoadingMotion.fade(400f, 100f), 0f)
        assertEquals(1f, BrowseLoadingMotion.fade(2000f, 100f), 0f)
        val values = (0..100).map { BrowseLoadingMotion.fade(it * 4f, 0f) }
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun rowDoesNotContractUntilLastItemFinishesThenEasesForFixedDuration() {
        val rowEnd = 500f
        assertEquals(1f, BrowseLoadingMotion.reservation(450f, rowEnd), 0f)
        assertEquals(1f, BrowseLoadingMotion.reservation(500f, rowEnd), 0f)
        assertEquals(.5f, BrowseLoadingMotion.reservation(620f, rowEnd), .0001f)
        assertEquals(0f, BrowseLoadingMotion.reservation(740f, rowEnd), 0f)
    }

    @Test fun artistOnlyStartsCircularButMixedArtistsShareOneRoundedToCircleContour() {
        assertEquals(8f, browseLoadingArtCorner(8f, 100f, artist = true, artistOnly = false, phase = 0f), 0f)
        assertEquals(29f, browseLoadingArtCorner(8f, 100f, artist = true, artistOnly = false, phase = .5f), 0f)
        assertEquals(50f, browseLoadingArtCorner(8f, 100f, artist = true, artistOnly = false, phase = 1f), 0f)
        assertEquals(50f, browseLoadingArtCorner(8f, 100f, artist = true, artistOnly = true, phase = 0f), 0f)
        assertEquals(8f, browseLoadingArtCorner(8f, 100f, artist = false, artistOnly = false, phase = .5f), 0f)
    }

    @Test fun placeholdersUseRealGeometryForEveryDensityAndArtworkFreeLists() {
        for (columns in 1..5) {
            val geometry = BrowseCellGeometry(columns)
            val width = geometry.artworkWidth(420, 1f)
            val metrics = BrowseItemMetrics(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), columns, width, 1f, false)
            assertEquals(width, metrics.art)
            assertEquals(2, metrics.lines)
            assertEquals(0, metrics.menu)
            assertEquals(metrics.place(32, width / 2, 32).height, metrics.art + 80)
        }
        for (size in ListSize.entries) {
            val metrics = BrowseItemMetrics(BrowseStyle(listSize = size), 1, 380, 1f, false)
            assertEquals(1, metrics.lines)
            assertEquals(size.trailingInformation, metrics.trailing)
            assertEquals(size in setOf(ListSize.Small, ListSize.Smallest), metrics.art == 0)
            assertEquals(48, metrics.menu)
        }
    }
}
