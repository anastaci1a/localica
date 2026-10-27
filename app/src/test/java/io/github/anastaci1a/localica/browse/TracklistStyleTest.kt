package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class TracklistStyleTest {
    @Test fun tracklistIsAvailableInAlbumAndMutableContentsNotCatalogs() {
        val order = listOf(ListSize.Smallest, ListSize.Small, ListSize.Tracklist, ListSize.Compact, ListSize.Normal, ListSize.Large)
        assertEquals(order, ListSize.densityOrder)
        assertEquals(listOf("Compact", "Normal", "Large", "Small", "Smallest", "Tracklist"), ListSize.entries.map { it.name })
        for (kind in listOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Queue)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "collection"))
            assertEquals(order, route.listSizes())
        }
        for (tab in listOf(MainTab.Library, MainTab.Albums, MainTab.Artists, MainTab.Songs)) {
            assertFalse(BrowseRoute(tab).listSizes().contains(ListSize.Tracklist))
            assertEquals(order.filter { it != ListSize.Tracklist }, BrowseRoute(tab).listSizes())
        }
        val album = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "album"))
        assertEquals(ListSize.Tracklist, album.defaultListSize())
        assertEquals(ListSize.Normal, BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "q")).defaultListSize())
    }

    @Test fun sharedSearchTemplateCannotGiveGlobalResultsNumberedCollectionRows() {
        val scoped = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "album"), search = true)
        val global = BrowseRoute(MainTab.Library, search = true, globalSearch = true)
        assertEquals(ListSize.Tracklist, scoped.resolveListSize(ListSize.Tracklist))
        assertEquals(ListSize.Normal, global.resolveListSize(ListSize.Tracklist))
        assertEquals(ListSize.Large, global.resolveListSize(ListSize.Large))
        assertEquals(ListSize.Normal, global.resolveListSize(null))
    }

    @Test fun numberedTracklistUsesAlbumNumberOrDisplayedOccurrenceNotOrdinaryNormal() {
        val numbered = BrowseStyle(listSize = ListSize.Tracklist)
        assertEquals("12", numbered.trackNumberLabel("12", 2))
        assertEquals("3", numbered.trackNumberLabel(null, 2))
        assertNull(BrowseStyle().trackNumberLabel("12", 2))
        assertNull(BrowseStyle(listSize = ListSize.Large).trackNumberLabel("12", 2))
        assertNull(numbered.copy(presentation = Presentation.Standard).trackNumberLabel("12", 2))
        for (size in listOf(ListSize.Smallest, ListSize.Small)) {
            assertEquals("12", BrowseStyle(listSize = size).trackNumberLabel("12", 2))
            assertNull(BrowseStyle(listSize = size).trackNumberLabel(null, 2))
        }
        // Filtering changes the displayed index, not the mutable occurrence's position.
        for (size in listOf(ListSize.Smallest, ListSize.Small, ListSize.Tracklist)) {
            val style = BrowseStyle(listSize = size)
            assertEquals("46", style.trackNumberLabel(null, 0, mutablePosition = 46))
            assertEquals("46", style.trackNumberLabel(null, 2, mutablePosition = 46))
            assertEquals("96", style.trackNumberLabel(null, 2, mutablePosition = 96))
            assertEquals("12", style.trackNumberLabel("12", 2, mutablePosition = 46))
            assertNull(style.copy(presentation = Presentation.Standard).trackNumberLabel(null, 2, 46))
        }
        for (size in listOf(ListSize.Compact, ListSize.Normal, ListSize.Large))
            assertNull(BrowseStyle(listSize = size).trackNumberLabel(null, 2, mutablePosition = 46))
        assertNull(BrowseStyle(listSize = ListSize.Small).trackNumberLabel(null, 2, mutablePosition = 0))
    }

    @Test fun compactKeepsArtworkRegardlessOfAlbumNumbersOrMutableOccurrencePositions() {
        val compact = BrowseStyle(listSize = ListSize.Compact)
        for (albumNumber in listOf(null, "12", "0")) {
            for (position in listOf(null, 0, 46)) for (index in listOf(0, 2)) {
                assertNull(compact.trackNumberLabel(albumNumber, index, position))
                assertNull(compact.copy(presentation = Presentation.Standard)
                    .trackNumberLabel(albumNumber, index, position))
            }
        }
        // A neighboring density must not inherit Compact's artwork policy.
        assertEquals("12", compact.copy(listSize = ListSize.Small).trackNumberLabel("12", 2, 46))
        assertEquals("12", compact.copy(listSize = ListSize.Tracklist).trackNumberLabel("12", 2, 46))
        assertEquals("46", compact.copy(listSize = ListSize.Tracklist).trackNumberLabel(null, 2, 46))
        assertTrue(compact.listSize.trailingInformation)
        assertFalse(compact.copy(listSize = ListSize.Normal).listSize.trailingInformation)
    }
}
