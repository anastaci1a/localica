package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test

class BrowseZoomTest {
    @Test fun zoomTraversesEveryViewAndStopsAtBothEnds() {
        var style = BrowseStyle(listSize = ListSize.Smallest)
        assertEquals(style, style.zoomStep(false, 400f))
        for (size in listOf(ListSize.Small, ListSize.Compact, ListSize.Normal, ListSize.Large)) {
            style = style.zoomStep(true, 400f)
            assertEquals(Presentation.List, style.presentation)
            assertEquals(size, style.listSize)
        }
        for (columns in 5 downTo 1) {
            style = style.zoomStep(true, 400f)
            assertEquals(columns, galleryColumns(style.presentation, 400f, style.itemsPerRow))
            assertNotEquals(Presentation.List, style.presentation)
        }
        assertEquals(style, style.zoomStep(true, 400f))
        for (columns in 2..5) {
            style = style.zoomStep(false, 400f)
            assertEquals(columns, style.itemsPerRow)
        }
        for (size in listOf(ListSize.Large, ListSize.Normal, ListSize.Compact, ListSize.Small, ListSize.Smallest)) {
            style = style.zoomStep(false, 400f)
            assertEquals(Presentation.List, style.presentation)
            assertEquals(size, style.listSize)
        }
        assertEquals(style, style.zoomStep(false, 400f))
    }

    @Test fun zoomKeepsSortAndOtherPreferencesIncludingAlbums() {
        val old = BrowseStyle(ascending = false, extraInfo = ExtraInfo.Year, quickSearch = false, songGrouping = SongGrouping.Artist)
        assertEquals(SongGrouping.Artist, old.zoomStep(true, 400f).songGrouping)
        val next = old.zoomStep(true, 400f, albumSongs = true)
        assertFalse(next.ascending)
        assertEquals(ExtraInfo.Year, next.extraInfo)
        assertFalse(next.quickSearch)
        assertEquals(old.copy(listSize = ListSize.Large), next)
    }
    @Test fun albumAndMutableListZoomIncludesTracklistOnlyBetweenCompactAndNormal() {
        // Retain the historical method identity; Tracklist now precedes Compact.
        val small = BrowseStyle(listSize = ListSize.Small)
        val compact = BrowseStyle(listSize = ListSize.Compact)
        val numbered = small.zoomStep(true, 400f, albumSongs = true)
        assertEquals(ListSize.Tracklist, numbered.listSize)
        assertEquals(compact, numbered.zoomStep(true, 400f, albumSongs = true))
        assertEquals(small, numbered.zoomStep(false, 400f, albumSongs = true))
        assertEquals(ListSize.Tracklist, compact.zoomStep(false, 400f, albumSongs = true).listSize)
        assertEquals(compact, BrowseStyle().zoomStep(false, 400f, albumSongs = true))
        assertEquals(ListSize.Normal, compact.zoomStep(true, 400f, albumSongs = true).listSize)
        assertEquals(ListSize.Normal, compact.zoomStep(true, 400f).listSize)
        assertEquals(compact, small.zoomStep(true, 400f))

        val sizes = listOf(ListSize.Smallest, ListSize.Small, ListSize.Tracklist, ListSize.Compact, ListSize.Normal, ListSize.Large)
        for ((from, to) in sizes.zipWithNext()) {
            val source = BrowseStyle(listSize = from, extraInfo = ExtraInfo.Year, ascending = false)
            val target = source.zoomStep(true, 400f, albumSongs = true)
            assertEquals(source.copy(listSize = to), target)
            assertEquals(source, target.zoomStep(false, 400f, albumSongs = true))
        }
    }

}
