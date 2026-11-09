package io.github.anastaci1a.localica.statistics

import io.github.anastaci1a.localica.library.LibrarySnapshot

data class ListeningStatistics(val tracks: Map<String, Long> = emptyMap(), val playlists: Map<String, Long> = emptyMap()) {
    fun applyTo(snapshot: LibrarySnapshot): LibrarySnapshot = snapshot.withPlayCounts(tracks, playlists)
}
