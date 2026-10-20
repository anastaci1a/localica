package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCatalogReadinessTest {
    @Test fun defaultLibraryWaitsForLibraryAndPlaylistsButNotQueues() {
        val route = BrowseRoute(MainTab.Library)
        assertFalse(browseCatalogReady(route, libraryLoading = true, playlistsLoading = false, queuesLoading = false))
        assertFalse(browseCatalogReady(route, libraryLoading = false, playlistsLoading = true, queuesLoading = false))
        assertFalse(browseCatalogReady(route, libraryLoading = true, playlistsLoading = true, queuesLoading = false))
        assertTrue(browseCatalogReady(route, libraryLoading = false, playlistsLoading = false, queuesLoading = true))
    }

    @Test fun unfilteredLibraryWaitsOnlyForIncludedCollectionSources() {
        val route = BrowseRoute(MainTab.Library)
        val albums = LibraryFilters(allCollections = setOf(MediaKind.Album))
        assertTrue(browseCatalogReady(route, false, true, true, albums))
        assertFalse(browseCatalogReady(route, true, false, false, albums))
        val playlists = LibraryFilters(allCollections = setOf(MediaKind.Playlist))
        assertFalse(browseCatalogReady(route, true, false, false, playlists))
        assertFalse(browseCatalogReady(route, false, true, false, playlists))
        assertTrue(browseCatalogReady(route, false, false, true, playlists))
        assertTrue(browseCatalogReady(route, true, true, true, LibraryFilters(allCollections = emptySet())))
    }

    @Test fun libraryFiltersAndDedicatedCatalogsDoNotWaitForUnrelatedSources() {
        val libraryKinds = listOf(MediaKind.Song, MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Folder)
        val dedicatedTabs = listOf(MainTab.Songs, MainTab.Albums, MainTab.Artists, MainTab.AlbumArtists, MainTab.Folders, MainTab.MostPlayed)
        val routes = libraryKinds.map { BrowseRoute(MainTab.Library, filter = it) } + dedicatedTabs.map { BrowseRoute(it) }
        for (route in routes) {
            assertTrue("$route should ignore unrelated loaders", browseCatalogReady(route, false, true, true))
            assertFalse("$route needs library metadata", browseCatalogReady(route, true, false, false))
        }
    }

    @Test fun libraryDetailsIgnoreUnrelatedSourcesAndTheirParentTab() {
        for (kind in listOf(MediaKind.Song, MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Folder)) {
            val route = BrowseRoute(MainTab.Playlists, collection = MediaRef(kind, "detail"))
            assertTrue(browseCatalogReady(route, false, true, true))
            assertFalse(browseCatalogReady(route, true, false, false))
        }
    }

    @Test fun playlistCatalogAndDetailsNeedCurrentLibraryMetadataAndPlaylistsOnly() {
        val routes = listOf(BrowseRoute(MainTab.Playlists),
            BrowseRoute(MainTab.Library, filter = MediaKind.Playlist),
            BrowseRoute(MainTab.Library, collection = MediaRef(MediaKind.Playlist, "mix")))
        for (route in routes) {
            assertFalse(browseCatalogReady(route, true, false, false))
            assertFalse(browseCatalogReady(route, false, true, false))
            assertTrue(browseCatalogReady(route, false, false, true))
        }
    }

    @Test fun queueDetailsNeedCurrentLibraryMetadataAndQueuesOnly() {
        val route = BrowseRoute(MainTab.Queues, collection = MediaRef(MediaKind.Queue, "queue"))
        assertFalse(browseCatalogReady(route, true, false, false))
        assertFalse(browseCatalogReady(route, false, false, true))
        assertTrue(browseCatalogReady(route, false, true, false))
    }

    @Test fun globalSearchUsesAllKindsRegardlessOfLibrarySettingsOrOrigin() {
        val route = BrowseRoute(MainTab.Queues, collection = MediaRef(MediaKind.Queue, "queue"),
            search = true, globalSearch = true, text = "mix")
        val noCollections = LibraryFilters(allCollections = emptySet())
        assertFalse(browseCatalogReady(route, true, false, false, noCollections))
        assertFalse(browseCatalogReady(route, false, true, false, noCollections))
        assertTrue(browseCatalogReady(route, false, false, true, noCollections))
        assertTrue(browseCatalogReady(route.copy(filter = MediaKind.Album), false, true, true, noCollections))
        assertFalse(browseCatalogReady(route.copy(filter = MediaKind.Playlist), false, true, false, noCollections))
    }

    @Test fun scopedSearchUsesItsCollectionInsteadOfGlobalSources() {
        val route = BrowseRoute(MainTab.Library, collection = MediaRef(MediaKind.Album, "album"),
            search = true, text = "song")
        assertTrue(browseCatalogReady(route, false, true, true))
        assertFalse(browseCatalogReady(route, true, false, false))
    }
}
