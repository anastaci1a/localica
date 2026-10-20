package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import java.util.UUID
import io.github.anastaci1a.localica.library.*

data class QueueEntry(val id: String = UUID.randomUUID().toString(), val track: Track, val sourcePlaylistId: String? = null)
data class ListeningQueue(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val entries: List<QueueEntry>,
    val currentEntryId: String? = entries.firstOrNull()?.id,
    val positionMs: Long = 0,
    // Legacy per-queue values are read only when migrating to global playback modes.
    val shuffle: Boolean = false,
    val repeat: QueueRepeat = QueueRepeat.Off,
    val lastActivatedAt: Long = createdAt,
    val journey: QueueJourney? = null,
    val order: SavedOrder = SavedOrder(entries.map { it.id }),
)
enum class QueueRepeat { Off, All, One }
enum class SongEnd(val label: String) { Stop("Stop here"), NextPaused("Load next song and pause"), Next("Play next song"), Repeat("Repeat this song") }
enum class QueueEnd(val label: String) { Stop("Stop here"), NextQueue("Go to next queue"), Repeat("Repeat this queue") }
data class AdvancedRepeat(val songEnd: SongEnd = SongEnd.Next, val queueEnd: QueueEnd = QueueEnd.Stop,
    val resetToFirst: Boolean = false, val reshuffle: Boolean = true) {
    val repeat: QueueRepeat get() = when {
        songEnd == SongEnd.Repeat -> QueueRepeat.One
        queueEnd == QueueEnd.Repeat -> QueueRepeat.All
        else -> QueueRepeat.Off
    }
    val pausesEachSong: Boolean get() = songEnd == SongEnd.Stop || songEnd == SongEnd.NextPaused
}
enum class SongTap { NewQueue, PlayNow, PlayNext, Append }
enum class QueueSwitch { Browse, Paused, Resume, KeepPlaybackState }
enum class PreviousBehavior { RestartAfterThreshold, PreviousSong, RestartSong }
data class QueueSettings(val songTap: SongTap = SongTap.NewQueue, val switching: QueueSwitch = QueueSwitch.Browse,
    val maximum: Int? = null, val tapOverrides: Map<SongListView, SongTap> = emptyMap(),
    val preloadNext: Boolean = true, val shuffle: Boolean = false, val repeat: QueueRepeat = QueueRepeat.Off,
    val previousBehavior: PreviousBehavior = PreviousBehavior.RestartAfterThreshold, val previousThresholdSeconds: Int = 5,
    val advancedRepeat: AdvancedRepeat? = null) {
    fun tapFor(view: SongListView) = tapOverrides[view] ?: songTap
    fun restartOnPrevious(positionMs: Long) = when (previousBehavior) {
        PreviousBehavior.RestartAfterThreshold -> positionMs >= previousThresholdSeconds * 1000L
        PreviousBehavior.PreviousSong -> false
        PreviousBehavior.RestartSong -> true
    }
}
data class QueueLibrary(val queues: List<ListeningQueue> = emptyList(), val activeId: String? = null) {
    val active: ListeningQueue? get() = queues.firstOrNull { it.id == activeId }
    fun update(queue: ListeningQueue) = copy(queues = queues.map { if (it.id == queue.id) queue else it })
}
data class PendingQueue(val tracks: List<Track>, val selectedIndex: Int, val suggestedRemoval: String?, val expandPlayer: Boolean = true, val sourceName: String = "All Songs", val playlistOrigins: List<String?> = emptyList())
data class QueueUiState(val library: QueueLibrary = QueueLibrary(), val loading: Boolean = true,
    val settings: QueueSettings = QueueSettings(), val pending: PendingQueue? = null, val problem: String? = null, val playerRequest: Long = 0, val browsedId: String? = null)

/** Pure queue edits keep occurrence identity separate from the song's library identity. */
object QueueEdits {
    /** Snapshot identity excludes generated occurrence IDs and mutable playback/metadata state. */
    fun equivalent(library: QueueLibrary, tracks: List<Track>, name: String,
        playlistOrigins: List<String?> = emptyList()): ListeningQueue? {
        fun matches(queue: ListeningQueue) = queue.name == name && queue.entries.size == tracks.size &&
            queue.entries.indices.all { index ->
                val entry = queue.entries[index]
                entry.track.id == tracks[index].id && entry.sourcePlaylistId == playlistOrigins.getOrNull(index)
            }
        return library.active?.takeIf(::matches) ?: library.queues.firstOrNull(::matches)
    }

    fun randomize(queue: ListeningQueue, keepCurrentFirst: Boolean, random: kotlin.random.Random = kotlin.random.Random.Default): ListeningQueue {
        val current = queue.entries.firstOrNull { keepCurrentFirst && it.id == queue.currentEntryId }
        val remaining = queue.entries.filterNot { it.id == current?.id }.shuffled(random)
        val entries = if (current == null) remaining else listOf(current) + remaining
        return queue.copy(entries = entries, order = SavedOrder(entries.map { it.id }))
    }

    fun create(tracks: List<Track>, selectedId: String, name: String): ListeningQueue {
        return create(tracks, tracks.indexOfFirst { it.id == selectedId }, name)
    }
    fun create(tracks: List<Track>, index: Int, name: String, playlistOrigins: List<String?> = emptyList()): ListeningQueue {
        require(index in tracks.indices)
        val entries = tracks.mapIndexed { i, track -> QueueEntry(track = track, sourcePlaylistId = playlistOrigins.getOrNull(i)) }
        return ListeningQueue(name = name, entries = entries, currentEntryId = entries[index].id)
    }
    fun insert(queue: ListeningQueue, track: Track, action: SongTap): ListeningQueue {
        return insert(queue, listOf(track), action)
    }
    fun insert(queue: ListeningQueue, tracks: List<Track>, action: SongTap, playlistOrigins: List<String?> = emptyList()): ListeningQueue {
        require(action != SongTap.NewQueue && tracks.isNotEmpty())
        val added = tracks.mapIndexed { i, track -> QueueEntry(track = track, sourcePlaylistId = playlistOrigins.getOrNull(i)) }
        val current = queue.entries.indexOfFirst { it.id == queue.currentEntryId }
        val position = if (action == SongTap.Append) queue.entries.size else (current + 1).coerceAtLeast(0)
        val entries = queue.entries.toMutableList().apply { addAll(position, added) }
        val select = action == SongTap.PlayNow || queue.currentEntryId == null
        return queue.copy(entries = entries, order = if (action == SongTap.Append) queue.order.copy(manual = queue.order.manual + added.map { it.id })
                else queue.order.reconcile(entries.map { it.id }), currentEntryId = if (select) added.first().id else queue.currentEntryId,
            positionMs = if (select) 0 else queue.positionMs)
    }
    fun remove(queue: ListeningQueue, entryId: String): ListeningQueue {
        val index = queue.entries.indexOfFirst { it.id == entryId }
        if (index < 0) return queue
        val entries = queue.entries.filterNot { it.id == entryId }
        val currentRemoved = queue.currentEntryId == entryId
        return queue.copy(entries = entries, order = queue.order.reconcile(entries.map { it.id }),
            currentEntryId = if (currentRemoved) entries.getOrNull(index.coerceAtMost(entries.lastIndex))?.id else queue.currentEntryId,
            positionMs = if (currentRemoved) 0 else queue.positionMs)
    }
    fun move(queue: ListeningQueue, entryId: String, offset: Int): ListeningQueue {
        val from = queue.entries.indexOfFirst { it.id == entryId }
        if (from < 0) return queue
        val entries = io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(queue.entries, from, offset)
        return reordered(queue, entries)
    }
    private fun reordered(queue: ListeningQueue, entries: List<QueueEntry>) = if (entries == queue.entries) queue
        else queue.copy(entries = entries, order = SavedOrder(entries.map { it.id }))
    fun moveBefore(queue: ListeningQueue, ids: Set<String>, beforeId: String?) = reordered(queue,
        OccurrenceMoves.before(queue.entries, ids, beforeId) { it.id })
    fun sort(queue: ListeningQueue, method: TrackSort, ascending: Boolean, snapshot: LibrarySnapshot? = null): ListeningQueue {
        val byId = queue.entries.associateBy { it.id }
        val rows = queue.entries.map { SongRow(it.id, it.track) }
        val ids = snapshot?.let { LiveOrder(it).target(queue.order, rows, method, ascending) }
            ?: queue.order.apply(rows, method, ascending)
        return queue.copy(entries = ids.map { byId.getValue(it) }, order = queue.order.copy(sort = method, ascending = ascending))
    }
    fun revertOrder(queue: ListeningQueue, checkpoint: OrderCheckpoint): ListeningQueue {
        val current = queue.entries.associateBy { it.id }
        val restored = checkpoint.reconcile(queue.entries.map { it.id })
        return queue.copy(entries = restored.physical.map(current::getValue), order = restored.saved)
    }
    fun oldestInactive(library: QueueLibrary): String? = library.queues.filterNot { it.id == library.activeId }
        .minWithOrNull(compareBy<ListeningQueue> { it.createdAt }.thenBy { it.id })?.id
    fun canCreate(library: QueueLibrary, maximum: Int?, removals: Set<String>): Boolean =
        library.activeId !in removals && (maximum == null || library.queues.count { it.id !in removals } < maximum)
}
