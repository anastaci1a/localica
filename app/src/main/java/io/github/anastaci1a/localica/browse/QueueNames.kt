package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*

/** Describe the originating list, never the randomly selected song. */
internal fun queueSourceName(route: BrowseRoute, tracks: List<Track>): String {
    val artists = tracks.mapNotNull { it.albumArtist?.takeIf(String::isNotBlank) ?: it.artist?.takeIf(String::isNotBlank) }.distinct()
    val artist = artists.singleOrNull()
    val name = when {
        route.search && route.text.isNotBlank() -> "Search: ${route.text}"
        route.collection?.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist) -> "All Songs"
        route.collection != null -> route.title ?: route.collection.kind.label
        route.tab == MainTab.Home -> "Pinned songs"
        else -> "All Songs"
    }
    val by = if (route.collection?.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) route.title else artist
    return name + (by?.takeIf { it.isNotBlank() }?.let { " ◀ $it" } ?: "")
}
