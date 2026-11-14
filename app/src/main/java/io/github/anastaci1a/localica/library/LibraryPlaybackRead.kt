package io.github.anastaci1a.localica.library

/** Fresh playback inputs. Room projects requested IDs; other stores return all tracks. */
data class LibraryPlaybackRead(
    val tracks: List<Track>,
    val totalTracks: Int,
)
