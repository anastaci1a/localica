package io.github.anastaci1a.localica.library

/** Fresh playback inputs. Room projects requested IDs; other stores preserve their full snapshot. */
data class LibraryPlaybackRead(
    val tracks: List<Track>,
    val totalTracks: Int,
    internal val fullSnapshot: LibrarySnapshot? = null,
)
