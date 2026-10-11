package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.compositionLocalOf
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue

internal data class PresentedBrowse(val route: BrowseRoute, val style: BrowseStyle, val result: BrowseResult)

/** Activity-local, bounded completed queries. Source identities avoid hashing a whole library on Main. */
internal class BrowsePresentationCache {
    private data class Entry(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val queues: List<ListeningQueue>, val filters: LibraryFilters, val presentation: PresentedBrowse)
    private val entries = mutableListOf<Entry>()
    fun get(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, route: BrowseRoute, style: BrowseStyle): PresentedBrowse? {
        val index = entries.indexOfFirst { it.library === library && it.playlists === playlists && it.queues === queues &&
            it.filters == filters && it.presentation.route == route && it.presentation.style == style }
        return if (index < 0) null else entries.removeAt(index).also { entries.add(it) }.presentation
    }
    fun put(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, presentation: PresentedBrowse) {
        entries.removeAll { it.library !== library || (it.presentation.route == presentation.route && it.presentation.style == presentation.style) }
        entries.add(Entry(library, playlists, queues, filters, presentation))
        while (entries.size > 12) entries.removeAt(0)
    }
}

internal val LocalBrowsePresentationCache = compositionLocalOf<BrowsePresentationCache?> { null }
