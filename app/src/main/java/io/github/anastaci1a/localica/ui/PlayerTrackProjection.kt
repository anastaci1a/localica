package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.QueueLibrary

internal data class PlayerTrackProjection(val current: Track?, val indexed: Track?, val lyrics: Track?)

/** Playback position does not change occurrence or metadata ownership. Inputs are immutable snapshots. */
@Composable
internal fun rememberPlayerTrackProjection(queues: QueueLibrary, library: LibrarySnapshot): PlayerTrackProjection {
    val active = remember(queues.queues, queues.activeId) { queues.active }
    val current = remember(active?.entries, active?.currentEntryId) {
        active?.let { queue -> queue.entries.firstOrNull { it.id == queue.currentEntryId }?.track }
    }
    // These callers historically choose the FIRST matching track, unlike associateBy's last match.
    val available = remember(library.tracks, current?.id) {
        current?.let { saved -> library.tracks.firstOrNull { it.id == saved.id } }
    }
    val indexed = remember(available, current, library.trackPlays) {
        available ?: current?.let { it.copy(playCount = library.trackPlays[it.id] ?: 0) }
    }
    // Lyrics retain the saved track unchanged when unavailable; actions use current fallback statistics.
    return remember(current, indexed, available) { PlayerTrackProjection(current, indexed, available ?: current) }
}
