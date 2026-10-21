package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class TracklistStyleTest {
    @Test fun tracklistIsAvailableInAlbumAndMutableContentsNotCatalogs() {
        for (kind in listOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Queue)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "collection"))
            assertEquals(ListSize.densityOrder, route.listSizes())
        }
        for (tab in listOf(MainTab.Library, MainTab.Albums, MainTab.Artists, MainTab.Songs))
            assertFalse(BrowseRoute(tab).listSizes().contains(ListSize.Tracklist))
        val album = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "album"))
        assertEquals(ListSize.Tracklist, album.defaultListSize())
        assertEquals(ListSize.Normal, BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "q")).defaultListSize())
    }

    @Test fun numberedTracklistUsesAlbumNumberOrDisplayedOccurrenceNotOrdinaryNormal() {
        val numbered = BrowseStyle(listSize = ListSize.Tracklist)
        assertEquals("12", numbered.trackNumberLabel("12", 2))
        assertEquals("3", numbered.trackNumberLabel(null, 2))
        assertNull(BrowseStyle().trackNumberLabel("12", 2))
        assertNull(BrowseStyle(listSize = ListSize.Large).trackNumberLabel("12", 2))
        assertNull(numbered.copy(presentation = Presentation.Standard).trackNumberLabel("12", 2))
        for (size in listOf(ListSize.Smallest, ListSize.Small, ListSize.Compact)) {
            assertEquals("12", BrowseStyle(listSize = size).trackNumberLabel("12", 2))
            assertNull(BrowseStyle(listSize = size).trackNumberLabel(null, 2))
        }
    }
}
