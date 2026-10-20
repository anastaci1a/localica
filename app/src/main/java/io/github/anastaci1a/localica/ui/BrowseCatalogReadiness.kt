package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseQueries
import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.MediaKind

/** Wait for the sources this query consumes, including current metadata for saved occurrences. */
internal fun browseCatalogReady(route: BrowseRoute, libraryLoading: Boolean, playlistsLoading: Boolean,
    queuesLoading: Boolean, libraryFilters: LibraryFilters = LibraryFilters()): Boolean {
    val kinds = when {
        route.globalSearch -> route.filter?.let(::listOf) ?: BrowseQueries.libraryKinds
        route.collection != null -> listOf(route.collection.kind)
        route.tab == MainTab.Library -> route.filter?.let(::listOf) ?:
            BrowseQueries.libraryKinds.filter { it in libraryFilters.allCollections && it != MediaKind.Song }
        route.tab == MainTab.Songs || route.tab == MainTab.MostPlayed -> listOf(MediaKind.Song)
        route.tab == MainTab.Albums -> listOf(MediaKind.Album)
        route.tab == MainTab.Artists -> listOf(MediaKind.Artist)
        route.tab == MainTab.AlbumArtists -> listOf(MediaKind.AlbumArtist)
        route.tab == MainTab.Folders -> listOf(MediaKind.Folder)
        route.tab == MainTab.Playlists -> listOf(MediaKind.Playlist)
        else -> emptyList()
    }
    // Queues are not part of Library/global search; BrowseQueries reads them only in a queue detail.
    val needsQueues = !route.globalSearch && route.collection?.kind == MediaKind.Queue
    val needsLibrary = needsQueues || kinds.any { it != MediaKind.Queue }
    return (!needsLibrary || !libraryLoading) &&
        (MediaKind.Playlist !in kinds || !playlistsLoading) && (!needsQueues || !queuesLoading)
}
