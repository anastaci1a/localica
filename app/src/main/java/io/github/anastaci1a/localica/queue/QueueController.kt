package io.github.anastaci1a.localica.queue

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** In-process command gateway. Only the service attaches a player; every mutation is serialized here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueController(private val dao: QueueDao, private val preferences: QueuePreferences,
                      private val library: LibraryRepository,
                      private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(QueueUiState(settings = preferences.read()))
    val state = mutable.asStateFlow()
    private var player: Player? = null
    private var traversal = emptyList<String>()
    private var loadedQueueId: String? = null
    private var generation = 0
    private var restored = false
    private var persistedLibrary = QueueLibrary()
    private var pendingComplete: ((Boolean) -> Unit)? = null
    private var editing: EditPlayback? = null
    private var editPlayRequest: Boolean? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { submit { capture() } }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) enqueueSongEnd()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player?.playWhenReady == true) enqueueSongEnd()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val policy = state.value.settings.advancedRepeat ?: return
            val attached = player ?: return
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION || !policy.reshuffle || policy.pausesEachSong ||
                policy.songEnd == SongEnd.Repeat || policy.queueEnd != QueueEnd.Repeat || !state.value.settings.shuffle) return
            if (oldPosition.mediaItem?.mediaId == traversal.lastOrNull() && newPosition.mediaItem?.mediaId == traversal.firstOrNull()) {
                val expected = generation
                submit { if (player === attached && generation == expected) applyPlaybackModes(resetShuffle = true) }
            }
        }
    }

    private fun enqueueSongEnd() {
        val attached = player ?: return
        val entry = attached.currentMediaItem?.mediaId ?: return
        val expected = generation
        val position = attached.currentPosition
        val policy = state.value.settings.advancedRepeat
        attached.pause()
        submit {
            // A song tap/seek or service replacement queued ahead of this callback wins.
            if (player !== attached || generation != expected || attached.currentMediaItem?.mediaId != entry ||
                attached.currentPosition < position - 250 || state.value.settings.advancedRepeat != policy) return@submit
            if (policy == null) { capture(); return@submit }
            val next = attached.nextMediaItemIndex
            if (policy.songEnd == SongEnd.Stop && next != C.INDEX_UNSET) { capture(); return@submit }
            if (policy.songEnd == SongEnd.NextPaused && next != C.INDEX_UNSET) {
                attached.seekTo(next, 0); capture(); return@submit
            }
            finishQueue(policy, play = !policy.pausesEachSong)
        }
    }

    private suspend fun finishQueue(policy: AdvancedRepeat, play: Boolean) {
        val attached = player ?: return
        capture()
        val queue = state.value.library.active ?: return
        val firstId = if (state.value.settings.shuffle) traversal.firstOrNull() else queue.entries.firstOrNull()?.id
        when (policy.queueEnd) {
            QueueEnd.Stop -> {
                if (policy.resetToFirst) attached.seekTo(0, 0)
                if (policy.reshuffle) applyPlaybackModes(resetShuffle = true)
                capture()
            }
            QueueEnd.Repeat -> {
                val first = queue.entries.indexOfFirst { it.id == firstId }.coerceAtLeast(0)
                attached.seekTo(first, 0)
                if (policy.reshuffle) applyPlaybackModes(resetShuffle = true)
                attached.playWhenReady = play
                capture()
            }
            QueueEnd.NextQueue -> {
                val queues = state.value.library.queues
                val index = queues.indexOfFirst { it.id == queue.id }
                val next = (1 until queues.size).map { queues[(index + it) % queues.size] }.firstOrNull { it.entries.isNotEmpty() }
                if (policy.resetToFirst) attached.seekTo(0, 0)
                capture()
                if (next == null) return
                saveStartingPlayback(state.value.library.update(next.copy(lastActivatedAt = nextActivation())).copy(activeId = next.id)) {
                    activate(play, seek = true, resetShuffle = policy.reshuffle)
                }
                mutable.update { if (it.browsedId == null || it.browsedId == queue.id) it.copy(browsedId = next.id) else it }
            }
        }
    }

    init {
        scope.launch {
            try {
                val saved = dao.read()
                persistedLibrary = saved
                preferences.migratePlaybackModes(saved.active)
                mutable.update { it.copy(library = saved, settings = preferences.read(), loading = false) }; restored = true
            }
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
        configurePreloading()
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

    fun tap(tracks: List<Track>, selectedId: String) = tap(tracks, tracks.indexOfFirst { it.id == selectedId })
    fun tap(tracks: List<Track>, selectedIndex: Int, view: SongListView = SongListView.Songs, sourceName: String = "All Songs", playlistOrigins: List<String?> = emptyList()) = submit {
        perform(tracks, selectedIndex, state.value.settings.tapFor(view), batch = false, sourceName = sourceName, playlistOrigins = playlistOrigins)
    }
    /** Explicit selection actions bypass all tap preferences and preserve displayed occurrence order. */
    fun act(tracks: List<Track>, action: SongTap, playlistOrigins: List<String?> = emptyList(), complete: (Boolean) -> Unit = {}) = submit {
        try {
            val done = perform(tracks, 0, action, batch = true, playlistOrigins = playlistOrigins)
            if (done) complete(true) else if (state.value.pending != null) pendingComplete = complete else complete(false)
        } catch (failure: Exception) { complete(false); throw failure }
    }

    private suspend fun perform(tracks: List<Track>, selectedIndex: Int, action: SongTap, batch: Boolean, sourceName: String = "Selected songs", playlistOrigins: List<String?> = emptyList()): Boolean {
        val selected = tracks.getOrNull(selectedIndex) ?: return false
        pendingComplete?.invoke(false); pendingComplete = null
        if (state.value.pending != null) mutable.update { it.copy(pending = null) }
        capture()
        val current = state.value.library.active
        val expand = action == SongTap.NewQueue || action == SongTap.PlayNow
        if (action == SongTap.NewQueue || current == null) {
            if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, emptySet())) {
                mutable.update { it.copy(pending = PendingQueue(tracks.toList(), selectedIndex, QueueEdits.oldestInactive(it.library), expand, sourceName, playlistOrigins)) }
                return false
            } else create(tracks, selectedIndex, emptySet(), expand, sourceName, playlistOrigins)
        } else {
            val next = state.value.library.update(QueueEdits.insert(current, if (batch) tracks else listOf(selected), action, if (batch) playlistOrigins else listOf(playlistOrigins.getOrNull(selectedIndex))))
            if (action == SongTap.PlayNow) saveStartingPlayback(next) { activate(true, resetShuffle = true, refreshLocations = false) }
            else { save(next); activate(null) }
            if (action == SongTap.PlayNow) requestPlayer()
        }
        return true
    }

    fun confirmCreation(removals: Set<String>) = submit {
        val pending = state.value.pending ?: return@submit
        capture()
        if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, removals)) {
            mutable.update { it.copy(problem = "Select enough inactive queues to make room. The current queue is protected.") }
            return@submit
        }
        create(pending.tracks, pending.selectedIndex, removals, pending.expandPlayer, pending.sourceName, pending.playlistOrigins)
        pendingComplete?.invoke(true); pendingComplete = null
    }
    fun cancelCreation() = submit {
        mutable.update { it.copy(pending = null, problem = null) }
        pendingComplete?.invoke(false); pendingComplete = null
    }
    private suspend fun create(tracks: List<Track>, selectedIndex: Int, removals: Set<String>, expandPlayer: Boolean, sourceName: String, playlistOrigins: List<String?>) {
        val names = state.value.library.queues.filterNot { it.id in removals }.map { it.name }.toSet()
        var name = sourceName
        var suffix = 2
        while (name in names) name = "$sourceName (${suffix++})"
        val queue = QueueEdits.create(tracks, selectedIndex, name, playlistOrigins).copy(lastActivatedAt = nextActivation())
        val next = QueueLibrary(state.value.library.queues.filterNot { it.id in removals } + queue, queue.id)
        // These tracks came from the visible list. Start the decoder before serializing a
        // potentially large queue, and avoid re-reading the entire library on every song tap.
        saveStartingPlayback(next) { activate(play = true, refreshLocations = false) }
        mutable.update { it.copy(pending = null, browsedId = queue.id) }
        if (expandPlayer) requestPlayer()
    }

    private fun requestPlayer() { mutable.update { it.copy(playerRequest = it.playerRequest + 1) } }

    fun switchTo(id: String) = submit {
        if (state.value.library.queues.none { it.id == id }) return@submit
        mutable.update { it.copy(browsedId = id) }
        if (state.value.settings.switching == QueueSwitch.Browse) return@submit
        val wasPlaying = player?.isPlaying == true
        capture()
        val queue = state.value.library.queues.first { it.id == id }
        save(state.value.library.update(queue.copy(lastActivatedAt = nextActivation())).copy(activeId = id))
        val play = when (state.value.settings.switching) {
            QueueSwitch.Browse -> return@submit
            QueueSwitch.Paused -> false
            QueueSwitch.Resume -> true
            QueueSwitch.KeepPlaybackState -> wasPlaying
        }
        activate(play)
    }
    /** Resume a queue's saved cursor without treating the action as a song tap. */
    fun playQueue(id: String) = submit {
        capture()
        val queue = state.value.library.queues.firstOrNull { it.id == id } ?: return@submit
        save(state.value.library.update(queue.copy(lastActivatedAt = nextActivation())).copy(activeId = id))
        mutable.update { it.copy(browsedId = id) }
        activate(play = true, seek = true)
    }
    fun selectEntry(id: String) = submit {
        capture()
        val active = state.value.library.queues.firstOrNull { queue -> queue.entries.any { it.id == id } } ?: return@submit
        val next = state.value.library.update(active.copy(currentEntryId = id, positionMs = 0, lastActivatedAt = nextActivation())).copy(activeId = active.id)
        saveStartingPlayback(next) {
            val attached = player
            val index = active.entries.indexOfFirst { it.id == id }
            if (attached != null && loadedQueueId == active.id && editing == null &&
                index < attached.mediaItemCount && attached.getMediaItemAt(index).mediaId == id) {
                // The loaded timeline already has these occurrences and resolved locations.
                attached.seekTo(index, 0)
                applyPlaybackModes(resetShuffle = true)
                if (attached.playbackState == Player.STATE_IDLE) attached.prepare()
                attached.play()
            } else activate(play = true, seek = true, resetShuffle = true)
        }
        mutable.update { it.copy(browsedId = active.id) }
        requestPlayer()
    }
    private fun nextActivation() = maxOf(System.currentTimeMillis(), (state.value.library.queues.maxOfOrNull { it.lastActivatedAt } ?: 0) + 1)
    fun rename(id: String, name: String) = submit {
        val queue = state.value.library.queues.firstOrNull { it.id == id } ?: return@submit
        name.trim().takeIf { it.isNotEmpty() }?.let { save(state.value.library.update(queue.copy(name = it))) }
    }
    fun moveQueue(id: String, offset: Int) = submit {
        val queues = state.value.library.queues.toMutableList()
        val from = queues.indexOfFirst { it.id == id }
        if (from >= 0) {
            queues.add((from + offset).coerceIn(0, queues.lastIndex), queues.removeAt(from))
            save(state.value.library.copy(queues = queues))
        }
    }
    fun removeQueues(ids: Set<String>, protectActive: Boolean = false) = submit {
        val wasPlaying = player?.isPlaying == true
        capture()
        val before = state.value.library
        val removed = if (protectActive) ids - setOfNotNull(before.activeId) else ids
        val queues = before.queues.filterNot { it.id in removed }
        val activeRemoved = before.activeId in removed
        val recent = queues.maxWithOrNull(compareBy<ListeningQueue> { it.lastActivatedAt }.thenBy { it.createdAt }.thenBy { it.id })
        save(QueueLibrary(queues, if (activeRemoved) recent?.id else before.activeId))
        mutable.update { it.copy(browsedId = recent?.id) }
        if (activeRemoved) activate(play = wasPlaying)
    }
    fun removeEntry(id: String) = editQueue(null) { QueueEdits.remove(it, id) }
    fun removeEntries(ids: Set<String>, queueId: String? = null, complete: (Boolean) -> Unit = {}) = submit {
        try {
            capture()
            val active = state.value.library.queues.firstOrNull { it.id == (queueId ?: state.value.library.activeId) } ?: return@submit complete(false)
            val edited = active.entries.filter { it.id in ids }.fold(active) { queue, entry -> QueueEdits.remove(queue, entry.id) }
            save(state.value.library.update(edited)); if (active.id == state.value.library.activeId) activate(null); complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun moveEntry(id: String, offset: Int, queueId: String? = null, complete: (Boolean) -> Unit = {}) = editQueue(queueId, complete) { QueueEdits.move(it, id, offset) }
    fun moveBefore(queueId: String, entries: Set<String>, beforeId: String?, complete: (Boolean) -> Unit = {}) =
        editQueue(queueId, complete) { QueueEdits.moveBefore(it, entries, beforeId) }
    fun shuffle() = submit {
        updateSettings(state.value.settings.copy(shuffle = !state.value.settings.shuffle))
        applyPlaybackModes(resetShuffle = true)
    }
    fun repeat() = submit {
        updateSettings(state.value.settings.copy(advancedRepeat = null, repeat = QueueRepeat.entries[(state.value.settings.repeat.ordinal + 1) % QueueRepeat.entries.size]))
        applyPlaybackModes()
    }
    fun randomize(queueId: String, complete: (Boolean) -> Unit = {}) = editQueue(queueId, complete) { queue ->
        queue.copy(entries = queue.entries.shuffled())
    }
    private fun editQueue(queueId: String?, complete: (Boolean) -> Unit = {}, edit: (ListeningQueue) -> ListeningQueue) = submit {
        try {
        capture()
        val active = state.value.library.queues.firstOrNull { it.id == (queueId ?: state.value.library.activeId) } ?: return@submit complete(false)
        save(state.value.library.update(edit(active)))
        if (active.id == state.value.library.activeId) activate(null)
        complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun settings(value: QueueSettings) = submit {
        require(value.maximum == null || value.maximum > 0)
        require(value.previousThresholdSeconds in 1..60)
        val resetShuffle = state.value.settings.shuffle != value.shuffle
        updateSettings(value.copy(repeat = value.advancedRepeat?.repeat ?: value.repeat))
        configurePreloading()
        applyPlaybackModes(resetShuffle)
    }
    private fun updateSettings(value: QueueSettings) {
        preferences.write(value)
        mutable.update { it.copy(settings = value, problem = null) }
    }
    private fun applyPlaybackModes(resetShuffle: Boolean = false) {
        val attached = player ?: return
        val modes = state.value.settings
        val ids = (0 until attached.mediaItemCount).map { attached.getMediaItemAt(it).mediaId }
        if (modes.shuffle && attached is ExoPlayer) {
            traversal = shuffleTraversal(ids, attached.currentMediaItem?.mediaId, if (resetShuffle) emptyList() else traversal)
            val order = traversal.map { ids.indexOf(it) }.toIntArray()
            attached.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, kotlin.random.Random.nextLong()))
        } else if (!modes.shuffle) traversal = emptyList()
        val policy = modes.advancedRepeat
        (attached as? ExoPlayer)?.setPauseAtEndOfMediaItems(policy?.pausesEachSong == true)
        attached.repeatMode = when (if (policy?.pausesEachSong == true) QueueRepeat.Off else modes.repeat) { QueueRepeat.Off -> Player.REPEAT_MODE_OFF
            QueueRepeat.All -> Player.REPEAT_MODE_ALL; QueueRepeat.One -> Player.REPEAT_MODE_ONE }
        attached.shuffleModeEnabled = modes.shuffle
    }
    private fun configurePreloading() {
        (player as? ExoPlayer)?.setPreloadConfiguration(if (state.value.settings.preloadNext)
            ExoPlayer.PreloadConfiguration(5_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
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
    fun previous() = submit {
        player?.let {
            if (it.currentMediaItem != null) {
                if (state.value.settings.restartOnPrevious(it.currentPosition) || !it.hasPreviousMediaItem()) it.seekTo(0)
                else it.seekToPreviousMediaItem()
            }
        }
        capture()
    }

    fun dismissProblem() { mutable.update { it.copy(problem = null) } }
    data class EditPlayback(val entryId: String, val resume: Boolean, val queueId: String)
    fun beginFileEdit(trackId: String, complete: (EditPlayback?) -> Unit) = submit {
        capture()
        val source = player
        val queue = state.value.library.active
        val entry = queue?.entries?.firstOrNull { it.id == queue.currentEntryId }
        if (source != null && entry != null && queue.entries.any { it.track.id == trackId }) {
            val token = EditPlayback(entry.id, source.playWhenReady, queue.id)
            editing = token
            editPlayRequest = null
            // stop releases periods; clearing prevents a metadata refresh from reusing stale bytes.
            source.pause(); source.stop(); source.clearMediaItems(); loadedQueueId = null
            complete(token)
        } else complete(null)
    }
    fun endFileEdit(token: EditPlayback?, complete: (Boolean) -> Unit) = submit {
        try {
            if (token != null && editing == token) {
                editing = null
                val unchanged = state.value.library.activeId == token.queueId &&
                    state.value.library.active?.currentEntryId == token.entryId
                val resume = editPlayRequest ?: (unchanged && token.resume)
                editPlayRequest = null
                activate(resume, seek = true)
            }
            complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun refreshTrack(track: Track, complete: (Boolean) -> Unit) = submit {
        try {
            capture()
            save(state.value.library.copy(queues = state.value.library.queues.map { queue ->
                queue.copy(entries = queue.entries.map { entry -> if (entry.track.id == track.id) entry.copy(track = track) else entry })
            }))
            activate(null); complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }

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
        return before.update(queue.copy(currentEntryId = id, positionMs = source.currentPosition.coerceAtLeast(0)))
    }
    private suspend fun capture() {
        player?.let { attached ->
            capturedLibrary(attached)?.let { save(it) }
            val modes = state.value.settings
            val repeat = when (attached.repeatMode) { Player.REPEAT_MODE_ALL -> QueueRepeat.All
                Player.REPEAT_MODE_ONE -> QueueRepeat.One; else -> QueueRepeat.Off }
            if (modes.shuffle != attached.shuffleModeEnabled || (modes.advancedRepeat == null && modes.repeat != repeat)) {
                val resetShuffle = modes.shuffle != attached.shuffleModeEnabled
                updateSettings(modes.copy(shuffle = attached.shuffleModeEnabled, repeat = if (modes.advancedRepeat == null) repeat else modes.repeat))
                if (resetShuffle) applyPlaybackModes(true)
            }
        }
    }
    private suspend fun save(next: QueueLibrary) {
        persist(next)
        mutable.update { it.copy(library = next, problem = null) }
    }
    private suspend fun persist(next: QueueLibrary) {
        if (persistedLibrary != next) {
            withContext(Dispatchers.IO) { dao.save(persistedLibrary, next) }
            persistedLibrary = next
        }
    }
    private suspend fun saveStartingPlayback(next: QueueLibrary, start: suspend () -> Unit) {
        mutable.update { it.copy(library = next, problem = null) }
        // Commands remain serialized, and callbacks still complete only after persistence.
        // Retain the last durable snapshot so a failed write can be retried by the next capture.
        try { start() } finally { persist(next) }
    }
    private suspend fun activate(play: Boolean?, seek: Boolean = false, resetShuffle: Boolean = false, refreshLocations: Boolean = true) {
        if (editing != null) {
            if (play != null) editPlayRequest = play
            return
        }
        val attached = player ?: return
        val queue = state.value.library.active
        if (queue == null || queue.entries.isEmpty()) {
            loadedQueueId = queue?.id; traversal = emptyList(); attached.stop(); attached.clearMediaItems(); applyPlaybackModes(); return
        }
        // Refresh access locations from the current index without rewriting the saved queue snapshot.
        val items = withContext(Dispatchers.IO) {
            val currentLibrary = if (refreshLocations) library.snapshots.first().tracks.associateBy { it.id } else emptyMap()
            val tracks = library.playableTracks(queue.entries.map { currentLibrary[it.track.id] ?: it.track })
            queue.entries.zip(tracks) { entry, track -> queueMediaItem(entry.id, track, entry.sourcePlaylistId) }
        }
        if (player !== attached) return
        val index = queue.entries.indexOfFirst { it.id == queue.currentEntryId }.coerceAtLeast(0)
        val changedQueue = loadedQueueId != queue.id || attached.mediaItemCount == 0
        if (changedQueue) {
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
                    if (attached.getMediaItemAt(desiredIndex).localConfiguration?.uri != item.localConfiguration?.uri ||
                        attached.getMediaItemAt(desiredIndex).mediaMetadata != item.mediaMetadata) {
                        attached.replaceMediaItem(desiredIndex, item)
                    }
                }
            }
            if (seek || attached.currentMediaItem?.mediaId != queue.currentEntryId) attached.seekTo(index, queue.positionMs)
            if (attached.playbackState == Player.STATE_IDLE) attached.prepare()
        }
        loadedQueueId = queue.id
        applyPlaybackModes(resetShuffle || changedQueue)
        if (play != null) attached.playWhenReady = play
    }
}

internal fun queueMediaItem(entryId: String, track: Track, sourcePlaylistId: String? = null): MediaItem {
    val extras = Bundle().apply {
        putString("sourcePlaylistId", sourcePlaylistId)
        putString("trackId", track.id); putString("trackUri", track.uri); putString("fileName", track.fileName)
        track.durationMs?.let { putLong("durationMs", it) }
    }
    return MediaItem.Builder().setMediaId(entryId).setUri(track.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setAlbumArtist(track.albumArtist).setExtras(extras).build()).build()
}
