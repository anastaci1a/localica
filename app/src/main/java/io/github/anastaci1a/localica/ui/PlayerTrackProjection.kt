package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*

internal data class PlayerTrackProjection(val current: Track?, val indexed: Track?, val lyrics: Track?)
internal data class QueueRead(val queue: ListeningQueue, val index: Int, val duration: Long?, val current: Track?)
class QueuePresentation internal constructor(internal val state: QueueUiState, internal val library: LibrarySnapshot,
    internal val active: QueueRead?, internal val browsed: QueueRead?, internal val number: Int,
    internal val tracks: PlayerTrackProjection, internal val available: Track?)

private fun ListeningQueue.read(previous: QueueRead?): QueueRead {
    val unchanged = entries === previous?.queue?.entries
    var index = if (unchanged && currentEntryId == previous?.queue?.currentEntryId) previous?.index ?: -1 else -1
    var duration = if (unchanged) previous?.duration ?: 0L else 0L
    var knownDuration = unchanged && previous?.duration != null
    if (!unchanged) entries.forEachIndexed { at, entry ->
        if (index < 0 && entry.id == currentEntryId) index = at
        entry.track.durationMs?.let { duration += it; knownDuration = true }
    } else if (currentEntryId != previous?.queue?.currentEntryId) index = entries.indexOfFirst { it.id == currentEntryId }
    return QueueRead(this, index, duration.takeIf { knownDuration }, if (unchanged && index == previous?.index) previous?.current else entries.getOrNull(index)?.track)
}

internal fun prepareQueuePresentation(state: QueueUiState, library: LibrarySnapshot, previous: QueuePresentation?): QueuePresentation {
    var active = -1
    var browsed = -1
    state.library.queues.forEachIndexed { index, queue ->
        if (active < 0 && queue.id == state.library.activeId) active = index
        if (browsed < 0 && queue.id == state.browsedId) browsed = index
    }
    if (browsed < 0) browsed = active
    val playing = state.library.queues.getOrNull(active)?.read(previous?.active)
    val reading = if (browsed == active) playing else state.library.queues.getOrNull(browsed)?.read(previous?.browsed)
    val current = playing?.current
    val available = if (library.tracks === previous?.library?.tracks && current?.id == previous?.tracks?.current?.id)
        previous?.available else current?.let { saved -> library.tracks.firstOrNull { it.id == saved.id } }
    val tracks = if (current === previous?.tracks?.current && library.trackPlays === previous?.library?.trackPlays && available === previous?.available)
        previous?.tracks ?: PlayerTrackProjection(null, null, null)
        else PlayerTrackProjection(current, available ?: current?.let { it.copy(playCount = library.trackPlays[it.id] ?: 0) }, available ?: current)
    return QueuePresentation(state, library, playing, reading, browsed + 1, tracks, available)
}

@Composable
internal fun rememberQueuePresentation(state: QueueUiState, library: LibrarySnapshot): State<QueuePresentation?> {
    lateinit var result: State<QueuePresentation?>
    return rememberAsyncValue(state, library.tracks, library.trackPlays, retainPrevious = true) { prepareQueuePresentation(state, library, result.value) }.also { result = it }
}
