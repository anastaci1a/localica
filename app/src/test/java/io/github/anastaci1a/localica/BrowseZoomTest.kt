package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test

class BrowseZoomTest {
    @Test fun zoomTraversesEveryViewAndStopsAtBothEnds() {
        var style = BrowseStyle()
        assertEquals(style, style.zoomStep(false, 400f))
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
        assertEquals(Presentation.List, style.zoomStep(false, 400f).presentation)
    }

    @Test fun zoomKeepsSortAndOtherPreferencesWhileLeavingSectionedListMode() {
        val old = BrowseStyle(ascending = false, extraInfo = ExtraInfo.Year, quickSearch = false, songGrouping = SongGrouping.Artist)
        assertEquals(SongGrouping.Artist, old.zoomStep(true, 400f).songGrouping)
        val next = old.zoomStep(true, 400f, albumSongs = true)
        assertFalse(next.ascending)
        assertEquals(ExtraInfo.Year, next.extraInfo)
        assertFalse(next.quickSearch)
        assertEquals(SongGrouping.None, next.songGrouping)
    }
}
