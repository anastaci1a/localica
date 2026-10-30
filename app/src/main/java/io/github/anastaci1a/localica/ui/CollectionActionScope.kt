package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseResult
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.browse.BrowseQueries
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.sameSongInformationLocation
import io.github.anastaci1a.localica.playlist.ManualPlaylist

/** All collection actions consume the same currently displayed ordered occurrence snapshot. */
internal fun collectionActionTarget(ref: MediaRef, title: String, displayed: BrowseResult,
    coverUri: String? = null): BrowserItem = BrowserItem(ref, title, null, displayed.rows, coverUri = coverUri)

/** Empty explicit IDs is All. A short tap still focuses a single album, even for a one-album artist. */
internal object ArtistAlbumSelection {
    fun only(id: String): List<String> = listOf(id)
    fun shownSelected(explicit: List<String>, id: String): Boolean = explicit.isEmpty() || id in explicit
    fun reconcile(explicit: List<String>, albums: List<String>): List<String> {
        val available = albums.toSet()
        val valid = explicit.filter { it in available }.distinct()
        // Preserve explicit single focus; completing a multi-album set means All.
        return if (available.size > 1 && valid.size == available.size) emptyList() else valid
    }
    fun toggle(explicit: List<String>, id: String, albums: List<String>): List<String> {
        val valid = reconcile(explicit, albums)
        if (id !in albums) return valid
        // All covers are selected in All mode: toggle just this one out of that effective set.
        val effective = if (valid.isEmpty()) albums.distinct() else valid
        return reconcile(if (id in effective) effective - id else effective + id, albums)
        // Empty result means All again, never an empty song scope.
    }
}


/** Resolve the same ordered collection and access location for every song-information action. */
internal fun collectionItemForTrack(action: PlayerAction, track: Track, snapshot: LibrarySnapshot,
    playlists: List<ManualPlaylist>, sorts: BrowseSortSnapshot, checkActive: () -> Unit = {}): BrowserItem? {
    val tab = when (action) {
        PlayerAction.Album -> MainTab.Albums
        PlayerAction.Artist -> MainTab.Artists
        PlayerAction.AlbumArtist -> MainTab.AlbumArtists
        else -> MainTab.Folders
    }
    return BrowseQueries.evaluate(snapshot, playlists, BrowseRoute(tab), sorts = sorts, checkActive = checkActive).items
        .firstOrNull { item -> item.songs.any { it.track.id == track.id &&
            (action != PlayerAction.Folder || sameSongInformationLocation(it.track, track)) } }
}
