package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import java.util.UUID

data class QueueEntry(val id: String = UUID.randomUUID().toString(), val track: Track)
data class ListeningQueue(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val entries: List<QueueEntry>,
    val currentEntryId: String? = entries.firstOrNull()?.id,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: QueueRepeat = QueueRepeat.Off,
)
enum class QueueRepeat { Off, All, One }
enum class SongTap { NewQueue, PlayNow, PlayNext, Append }
enum class QueueSwitch { Paused, Resume, KeepPlaybackState }
data class QueueSettings(val songTap: SongTap = SongTap.NewQueue, val switching: QueueSwitch = QueueSwitch.Paused,
    val maximum: Int? = null, val tapOverrides: Map<SongListView, SongTap> = emptyMap(),
    val preloadNext: Boolean = true) {
    fun tapFor(view: SongListView) = tapOverrides[view] ?: songTap
}
data class QueueLibrary(val queues: List<ListeningQueue> = emptyList(), val activeId: String? = null) {
    val active: ListeningQueue? get() = queues.firstOrNull { it.id == activeId }
    fun update(queue: ListeningQueue) = copy(queues = queues.map { if (it.id == queue.id) queue else it })
}
data class PendingQueue(val tracks: List<Track>, val selectedIndex: Int, val suggestedRemoval: String?, val expandPlayer: Boolean = true)
data class QueueUiState(val library: QueueLibrary = QueueLibrary(), val loading: Boolean = true,
    val settings: QueueSettings = QueueSettings(), val pending: PendingQueue? = null, val problem: String? = null, val playerRequest: Long = 0)

/** Pure queue edits keep occurrence identity separate from the song's library identity. */
object QueueEdits {
    fun create(tracks: List<Track>, selectedId: String, name: String): ListeningQueue {
        return create(tracks, tracks.indexOfFirst { it.id == selectedId }, name)
    }
    fun create(tracks: List<Track>, index: Int, name: String): ListeningQueue {
        require(index in tracks.indices)
        val entries = tracks.map { QueueEntry(track = it) }
        return ListeningQueue(name = name, entries = entries, currentEntryId = entries[index].id)
    }
    fun insert(queue: ListeningQueue, track: Track, action: SongTap): ListeningQueue {
        return insert(queue, listOf(track), action)
    }
    fun insert(queue: ListeningQueue, tracks: List<Track>, action: SongTap): ListeningQueue {
        require(action != SongTap.NewQueue && tracks.isNotEmpty())
        val added = tracks.map { QueueEntry(track = it) }
        val current = queue.entries.indexOfFirst { it.id == queue.currentEntryId }
        val position = if (action == SongTap.Append) queue.entries.size else (current + 1).coerceAtLeast(0)
        val entries = queue.entries.toMutableList().apply { addAll(position, added) }
        val select = action == SongTap.PlayNow || queue.currentEntryId == null
        return queue.copy(entries = entries, currentEntryId = if (select) added.first().id else queue.currentEntryId,
            positionMs = if (select) 0 else queue.positionMs)
    }
    fun remove(queue: ListeningQueue, entryId: String): ListeningQueue {
        val index = queue.entries.indexOfFirst { it.id == entryId }
        if (index < 0) return queue
        val entries = queue.entries.filterNot { it.id == entryId }
        val currentRemoved = queue.currentEntryId == entryId
        return queue.copy(entries = entries,
            currentEntryId = if (currentRemoved) entries.getOrNull(index.coerceAtMost(entries.lastIndex))?.id else queue.currentEntryId,
            positionMs = if (currentRemoved) 0 else queue.positionMs)
    }
    fun move(queue: ListeningQueue, entryId: String, offset: Int): ListeningQueue {
        val from = queue.entries.indexOfFirst { it.id == entryId }
        if (from < 0) return queue
        val to = (from + offset).coerceIn(0, queue.entries.lastIndex)
        val entries = queue.entries.toMutableList().apply { add(to, removeAt(from)) }
        return queue.copy(entries = entries)
    }
    fun oldestInactive(library: QueueLibrary): String? = library.queues.filterNot { it.id == library.activeId }
        .minWithOrNull(compareBy<ListeningQueue> { it.createdAt }.thenBy { it.id })?.id
    fun canCreate(library: QueueLibrary, maximum: Int?, removals: Set<String>): Boolean =
        library.activeId !in removals && (maximum == null || library.queues.count { it.id !in removals } < maximum)
}
