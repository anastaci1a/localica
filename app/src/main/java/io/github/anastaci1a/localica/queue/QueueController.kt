package io.github.anastaci1a.localica.queue

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** In-process command gateway. Only the service attaches a player; every mutation is serialized here. */
class QueueController(private val dao: QueueDao, private val preferences: QueuePreferences,
                      private val library: LibraryRepository,
                      private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(QueueUiState(settings = preferences.read()))
    val state = mutable.asStateFlow()
    private var player: Player? = null
    private var loadedQueueId: String? = null
    private var generation = 0
    private var restored = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { submit { capture() } }
    }

    init {
        scope.launch {
            try { mutable.update { it.copy(library = dao.read(), loading = false) }; restored = true }
            catch (_: Exception) { mutable.update { it.copy(loading = false, problem = "Couldn’t restore queues. Reopen the app to retry.") } }
            for (command in commands) {
                try {
                    if (restored) command()
                    else mutable.update { it.copy(problem = "Queues are unavailable. Reopen the app to retry restoring them.") }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(problem = "Couldn’t save this queue change. Try again.") } }
            }
        }
        scope.launch { while (isActive) { delay(5000); if (restored) submit { capture() } } }
    }

    private fun submit(command: suspend () -> Unit) { commands.trySend(command) }
    fun attach(attached: Player) {
        val expected = ++generation
        submit {
        if (expected != generation) return@submit
        player?.removeListener(listener)
        player = attached
        loadedQueueId = null
        attached.addListener(listener)
        activate(play = false)
        }
    }
    fun detach(attached: Player) {
        generation++
        // Take the final cursor before MediaSessionService releases the player.
        val captured = capturedLibrary(attached)
        attached.removeListener(listener)
        if (player === attached) player = null
        submit {
            val cursor = captured?.active
            val current = state.value.library.active
            if (cursor != null && current?.id == cursor.id && current.currentEntryId == cursor.currentEntryId) {
                save(state.value.library.update(current.copy(positionMs = cursor.positionMs)))
            }
        }
    }

    fun tap(tracks: List<Track>, selectedId: String) = submit {
        capture()
        val selected = tracks.firstOrNull { it.id == selectedId } ?: return@submit
        val current = state.value.library.active
        val action = state.value.settings.songTap
        if (action == SongTap.NewQueue || current == null) {
            if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, emptySet())) {
                mutable.update { it.copy(pending = PendingQueue(tracks.toList(), selectedId, QueueEdits.oldestInactive(it.library))) }
            } else create(tracks, selectedId, emptySet())
        } else {
            val play = if (action == SongTap.PlayNow) true else null
            save(state.value.library.update(QueueEdits.insert(current, selected, action)))
            activate(play)
        }
    }

    fun confirmCreation(removals: Set<String>) = submit {
        val pending = state.value.pending ?: return@submit
        capture()
        if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, removals)) {
            mutable.update { it.copy(problem = "Select enough inactive queues to make room. The current queue is protected.") }
            return@submit
        }
        create(pending.tracks, pending.selectedId, removals)
    }
    fun cancelCreation() = submit { mutable.update { it.copy(pending = null, problem = null) } }
    private suspend fun create(tracks: List<Track>, selectedId: String, removals: Set<String>) {
        val selected = tracks.first { it.id == selectedId }
        val names = state.value.library.queues.filterNot { it.id in removals }.map { it.name }.toSet()
        var name = selected.title
        var suffix = 2
        while (name in names) name = "${selected.title} (${suffix++})"
        val queue = QueueEdits.create(tracks, selectedId, name)
        save(QueueLibrary(state.value.library.queues.filterNot { it.id in removals } + queue, queue.id))
        mutable.update { it.copy(pending = null) }
        activate(play = true)
    }

    fun switchTo(id: String) = submit {
        if (state.value.library.queues.none { it.id == id }) return@submit
        val wasPlaying = player?.playWhenReady == true
        capture()
        save(state.value.library.copy(activeId = id))
        val play = when (state.value.settings.switching) {
            QueueSwitch.Paused -> false
            QueueSwitch.Resume -> true
            QueueSwitch.KeepPlaybackState -> wasPlaying
        }
        activate(play)
    }
    fun selectEntry(id: String) = submit {
        capture()
        val active = state.value.library.active ?: return@submit
        if (active.entries.none { it.id == id }) return@submit
        save(state.value.library.update(active.copy(currentEntryId = id, positionMs = 0)))
        activate(play = true, seek = true)
    }
    fun rename(id: String, name: String) = submit {
        val queue = state.value.library.queues.firstOrNull { it.id == id } ?: return@submit
        name.trim().takeIf { it.isNotEmpty() }?.let { save(state.value.library.update(queue.copy(name = it))) }
    }
    fun removeQueues(ids: Set<String>, protectActive: Boolean = false) = submit {
        capture()
        val before = state.value.library
        val removed = if (protectActive) ids - setOfNotNull(before.activeId) else ids
        val queues = before.queues.filterNot { it.id in removed }
        val activeRemoved = before.activeId in removed
        save(QueueLibrary(queues, if (activeRemoved) queues.firstOrNull()?.id else before.activeId))
        if (activeRemoved) activate(play = false)
    }
    fun removeEntry(id: String) = editActive { QueueEdits.remove(it, id) }
    fun moveEntry(id: String, offset: Int) = editActive { QueueEdits.move(it, id, offset) }
    fun shuffle() = editActive { it.copy(shuffle = !it.shuffle) }
    fun repeat() = editActive { it.copy(repeat = QueueRepeat.entries[(it.repeat.ordinal + 1) % QueueRepeat.entries.size]) }
    private fun editActive(edit: (ListeningQueue) -> ListeningQueue) = submit {
        capture()
        val active = state.value.library.active ?: return@submit
        save(state.value.library.update(edit(active)))
        activate(null)
    }
    fun settings(value: QueueSettings) = submit {
        require(value.maximum == null || value.maximum > 0)
        preferences.write(value)
        mutable.update { it.copy(settings = value, problem = null) }
    }
    fun togglePlayback() = submit {
        player?.run {
            if (currentMediaItem == null) return@run
            if (playWhenReady && playbackState != Player.STATE_ENDED && playerError == null) pause()
            else {
                if (playbackState == Player.STATE_ENDED) seekTo(0)
                if (playbackState == Player.STATE_IDLE || playerError != null) prepare()
                play()
            }
        }
        capture()
    }
    fun seek(positionMs: Long) = submit {
        player?.let { if (it.duration != C.TIME_UNSET && it.duration > 0) it.seekTo(positionMs.coerceIn(0, it.duration)) }
        capture()
    }
    fun next() = submit { player?.seekToNextMediaItem(); capture() }
    fun previous() = submit { player?.seekToPreviousMediaItem(); capture() }

    fun dismissProblem() { mutable.update { it.copy(problem = null) } }

    /** Used by the existing clear action; retained queues are intentionally kept. */
    fun clearPlayback() = submit {
        capture()
        save(state.value.library.copy(activeId = null))
        activate(false)
    }

    private fun capturedLibrary(source: Player): QueueLibrary? {
        val before = state.value.library
        val queue = before.active ?: return null
        val id = source.currentMediaItem?.mediaId ?: return null
        if (queue.entries.none { it.id == id }) return null
        return before.update(queue.copy(currentEntryId = id, positionMs = source.currentPosition.coerceAtLeast(0),
            shuffle = source.shuffleModeEnabled, repeat = when (source.repeatMode) {
                Player.REPEAT_MODE_ALL -> QueueRepeat.All
                Player.REPEAT_MODE_ONE -> QueueRepeat.One
                else -> QueueRepeat.Off
            }))
    }
    private suspend fun capture() { player?.let { capturedLibrary(it) }?.let { save(it) } }
    private suspend fun save(next: QueueLibrary) {
        val previous = state.value.library
        if (previous != next) withContext(Dispatchers.IO) { dao.save(previous, next) }
        mutable.update { it.copy(library = next, problem = null) }
    }
    private suspend fun activate(play: Boolean?, seek: Boolean = false) {
        val attached = player ?: return
        val queue = state.value.library.active
        if (queue == null || queue.entries.isEmpty()) {
            loadedQueueId = queue?.id; attached.stop(); attached.clearMediaItems(); return
        }
        // Refresh access locations from the current index without rewriting the saved queue snapshot.
        val items = withContext(Dispatchers.IO) {
            val currentLibrary = library.snapshots.first().tracks.associateBy { it.id }
            val tracks = library.playableTracks(queue.entries.map { currentLibrary[it.track.id] ?: it.track })
            queue.entries.zip(tracks) { entry, track -> queueMediaItem(entry.id, track) }
        }
        if (player !== attached) return
        val index = queue.entries.indexOfFirst { it.id == queue.currentEntryId }.coerceAtLeast(0)
        if (loadedQueueId != queue.id || attached.mediaItemCount == 0) {
            attached.setMediaItems(items, index, queue.positionMs)
            attached.prepare()
        } else {
            val wanted = items.map { it.mediaId }.toSet()
            for (oldIndex in attached.mediaItemCount - 1 downTo 0) {
                if (attached.getMediaItemAt(oldIndex).mediaId !in wanted) attached.removeMediaItem(oldIndex)
            }
            items.forEachIndexed { desiredIndex, item ->
                val existing = if (desiredIndex < attached.mediaItemCount && attached.getMediaItemAt(desiredIndex).mediaId == item.mediaId) desiredIndex
                    else (0 until attached.mediaItemCount).firstOrNull { attached.getMediaItemAt(it).mediaId == item.mediaId }
                if (existing == null) attached.addMediaItem(desiredIndex, item)
                else {
                    if (existing != desiredIndex) attached.moveMediaItem(existing, desiredIndex)
                    if (attached.getMediaItemAt(desiredIndex).localConfiguration?.uri != item.localConfiguration?.uri) {
                        attached.replaceMediaItem(desiredIndex, item)
                    }
                }
            }
            if (seek || attached.currentMediaItem?.mediaId != queue.currentEntryId) attached.seekTo(index, queue.positionMs)
            if (attached.playbackState == Player.STATE_IDLE) attached.prepare()
        }
        loadedQueueId = queue.id
        attached.repeatMode = when (queue.repeat) { QueueRepeat.Off -> Player.REPEAT_MODE_OFF
            QueueRepeat.All -> Player.REPEAT_MODE_ALL; QueueRepeat.One -> Player.REPEAT_MODE_ONE }
        attached.shuffleModeEnabled = queue.shuffle
        if (play != null) attached.playWhenReady = play
    }
}

internal fun queueMediaItem(entryId: String, track: Track): MediaItem {
    val extras = Bundle().apply {
        putString("trackId", track.id); putString("trackUri", track.uri); putString("fileName", track.fileName)
        track.durationMs?.let { putLong("durationMs", it) }
    }
    return MediaItem.Builder().setMediaId(entryId).setUri(track.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setExtras(extras).build()).build()
}
