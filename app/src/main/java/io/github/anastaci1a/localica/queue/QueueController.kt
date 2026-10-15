package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.OccurrenceMoves
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.RecoveryAdmission
import io.github.anastaci1a.localica.library.documentIdentity
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** In-process command gateway. Only the service attaches a player; every mutation is serialized here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueController(private val dao: QueueDao, private val preferences: QueuePreferences,
                      private val library: LibraryRepository,
                      private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                      private val readers: NativeReaders = NativeReaders()) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val commandJob: Job
    private val intents = PlaybackIntents()
    private var commandRevision = 0L
    private val mutable = MutableStateFlow(QueueUiState(settings = preferences.read()))
    val state = mutable.asStateFlow()
    private var player: Player? = null
    private val timeline = QueueTimeline()
    private var syncingTimeline = false
    private data class Arrival(val queueId: String, val token: String)
    /** Only synchronous normalization may create this value; publication consumes its exact source state. */
    private class PreparedPlayback(val source: QueueUiState, val library: QueueLibrary)
    private val arrivals = ArrayDeque<Arrival>()
    private var loadedQueueId: String? = null
    private var generation = 0
    private var restored = false
    private var persistedLibrary = QueueLibrary()
    private var pendingComplete: ((Boolean) -> Unit)? = null
    private var editing: EditPlayback? = null
    private var editPlayRequest: Boolean? = null
    private var lastPlayWhenReady = false
    private var pausedAtEnd = false
    private var recoveryRepeatSuppressed = false
    private data class RecoveryProblem(val message: String, val queueId: String,
        val entryId: String?, val blockedSource: String)
    private var recoveryProblem: RecoveryProblem? = null
    private var recoveryHeld: Pair<String, String?>? = null
    private var observedRecovery = readers.recovery()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!holdsForEdit(state.value.library.active)) intents.observe(nativePlayIntent(player))
            val ended = pausedAtEnd || (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED && player.playWhenReady)
            val requestedAtEnd = pausedAtEnd || player.playWhenReady
            pausedAtEnd = false
            if (ended) enqueueSongEnd(requestedAtEnd) else submit { capture() }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Media3 also reports reason-only changes. A late END reason after loading
            // the next paused visit must not advance a second time (observed on API 26).
            if (lastPlayWhenReady && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) pausedAtEnd = true
            lastPlayWhenReady = playWhenReady
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (syncingTimeline || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val id = mediaItem?.listeningQueue ?: return
            val token = mediaItem.queueVisit ?: return
            // Record each arrival synchronously: several transitions may happen while a save waits on disk.
            arrivals.addLast(Arrival(id, token))
            submit { capture() }
        }
    }

    private fun enqueueSongEnd(wasRequested: Boolean) {
        val attached = player ?: return
        val visit = attached.currentMediaItem?.queueVisit ?: return
        val expected = generation
        val position = attached.currentPosition
        val policy = state.value.settings.advancedRepeat
        val requestedAtEnd = intents.choose(fallback = wasRequested && attached.playerError == null).play
        attached.pause()
        submit {
            // A song tap/seek or service replacement queued ahead of this callback wins.
            if (player !== attached || generation != expected || attached.currentMediaItem?.queueVisit != visit ||
                attached.currentPosition < position - 250 || state.value.settings.advancedRepeat != policy) return@submit
            capture()
            val queue = state.value.library.active ?: return@submit
            val journey = queue.journey ?: return@submit
            val next = journey.next
            if (blocksVisit(queue, next)) { reportRecovery(); return@submit }
            if (policy == null) return@submit
            val sameCycle = next != null && next.cycle == journey.current?.cycle
            if (policy.songEnd == SongEnd.Stop && sameCycle) return@submit
            if (policy.songEnd == SongEnd.NextPaused && sameCycle) {
                moveTo(next!!); return@submit
            }
            finishQueue(policy, play = !policy.pausesEachSong && intents.choose(commandRevision, requestedAtEnd).play)
        }
    }

    private suspend fun finishQueue(policy: AdvancedRepeat, play: Boolean) {
        val attached = player ?: return
        capture()
        val queue = state.value.library.active ?: return
        suspend fun reset(): Boolean {
            val first = queue.journey?.plans?.get(queue.journey.cycle)?.firstOrNull()
                ?: queue.entries.firstOrNull()?.id ?: return false
            if (queue.entries.firstOrNull { it.id == first }?.track?.id?.let(::blocksSource) == true) {
                reportRecovery(); return false
            }
            val next = queue.copy(currentEntryId = first, positionMs = 0,
                journey = journey(queue).restart(queue.entries.map { it.id }, first))
            saveStartingPlayback(state.value.library.update(next)) { syncTimeline(seek = true) }
            return true
        }
        when (policy.queueEnd) {
            QueueEnd.Stop -> { if (policy.resetToFirst) reset() }
            QueueEnd.Repeat -> {
                val next = queue.journey?.next
                if (blocksVisit(queue, next)) { reportRecovery(); return }
                if (next != null) moveTo(next) else if (!reset()) return
                applyNativeIntent(attached, commandRevision, play)
                capture()
            }
            QueueEnd.NextQueue -> {
                val queues = state.value.library.queues
                val index = queues.indexOfFirst { it.id == queue.id }
                val next = (1 until queues.size).map { queues[(index + it) % queues.size] }.firstOrNull { it.entries.isNotEmpty() }
                if (next != null && blocksCurrent(next)) { reportRecovery(); return }
                if (policy.resetToFirst && !reset()) return
                if (next == null) return
                saveStartingPlayback(state.value.library.update(next.copy(lastActivatedAt = nextActivation())).copy(activeId = next.id)) {
                    activate(play, seek = true, recordIntent = false)
                }
                mutable.update { if (it.browsedId == null || it.browsedId == queue.id) it.copy(browsedId = next.id) else it }
            }
        }
    }

    init {
        commandJob = scope.launch {
            try {
                val saved = dao.read()
                persistedLibrary = saved
                preferences.migratePlaybackModes(saved.active)
                mutable.update { it.copy(library = saved, settings = preferences.read(), loading = false) }; restored = true
            }
            catch (_: Exception) { mutable.update { it.copy(loading = false, problem = "Couldn’t restore queues. Reopen the app to retry.") } }
            for (command in commands) {
                try {
                    // Main.immediate can otherwise resume this actor inside a Media3 listener.
                    // Finish the complete event batch before changing the native timeline again.
                    yield()
                    if (restored) command()
                    else reportProblem("Queues are unavailable. Reopen the app to retry restoring them.")
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportProblem("Couldn’t save this queue change. Try again.") }
            }
        }
        commandJob.invokeOnCompletion { commands.close() }
        scope.launch { readers.recoveryChanges.collect {
            // Notifications may coalesce or arrive after another publication. Read the authority.
            submit { refreshRecoveryPlayback() }
        } }
        scope.launch { while (isActive) { delay(5000); if (restored) submit { capture() } } }
    }

    private fun submit(revision: Long = intents.accept(), command: suspend () -> Unit) {
        commands.trySend { commandRevision = revision; command() }
    }
    fun attach(attached: Player) {
        val expected = ++generation
        submit {
        if (expected != generation) return@submit
        player?.removeListener(listener)
        player = attached
        loadedQueueId = null
        pausedAtEnd = false
        lastPlayWhenReady = attached.playWhenReady
        attached.addListener(listener)
        configurePreloading()
        activate(play = false, recordIntent = false)
        }
    }
    fun detach(attached: Player) {
        generation++
        // Take the final cursor before MediaSessionService releases the player.
        val item = attached.currentMediaItem
        val queueId = item?.listeningQueue
        val visit = item?.queueVisit
        val position = attached.currentPosition.coerceAtLeast(0)
        attached.removeListener(listener)
        if (player === attached) { player = null; intents.observe(false) }
        submit {
            // Fold arrivals after any pending edit commits, so its older snapshot cannot erase them.
            var captured = capturedLibrary(null)
            val queue = captured.queues.firstOrNull { it.id == queueId }
            if (queue != null && queue.journey?.current?.token == visit) {
                captured = captured.update(queue.copy(positionMs = position))
            }
            mutable.update { it.copy(library = captured) }
            persist(captured)
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

    fun appendTo(id: String, rows: List<io.github.anastaci1a.localica.library.SongRow>, complete: (Boolean) -> Unit = {}) = submit {
        try {
            capture()
            val target = state.value.library.queues.firstOrNull { it.id == id }
            if (target == null) { complete(false); return@submit }
            val next = QueueEdits.insert(target, rows.map { it.track }, SongTap.Append, rows.map { it.sourcePlaylistId })
            save(state.value.library.update(next))
            if (state.value.library.active?.id == id) activate(null)
            complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }

    /** Randomize the saved visible order without enabling traversal shuffle. */
    fun randomizedQueue(tracks: List<Track>, sourceName: String, playlistOrigins: List<String?> = emptyList()) = submit {
        val order = tracks.indices.shuffled()
        perform(order.map(tracks::get), 0, SongTap.NewQueue, batch = true, sourceName = sourceName,
            playlistOrigins = order.map { playlistOrigins.getOrNull(it) })
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
            var inserted = QueueEdits.insert(current, if (batch) tracks else listOf(selected), action, if (batch) playlistOrigins else listOf(playlistOrigins.getOrNull(selectedIndex)))
            if (action == SongTap.PlayNow) inserted = inserted.copy(journey = journey(current).select(
                requireNotNull(inserted.currentEntryId), inserted.entries.map { it.id }))
            val next = state.value.library.update(inserted)
            if (action == SongTap.PlayNow) saveStartingPlayback(next) { activate(true, seek = true, refreshLocations = false) }
            else { save(next); activate(null) }
            if (action == SongTap.PlayNow) requestPlayer()
        }
        return true
    }

    fun confirmCreation(removals: Set<String>) = submit {
        val pending = state.value.pending ?: return@submit
        capture()
        if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, removals)) {
            reportProblem("Select enough inactive queues to make room. The current queue is protected.")
            return@submit
        }
        create(pending.tracks, pending.selectedIndex, removals, pending.expandPlayer, pending.sourceName, pending.playlistOrigins)
        pendingComplete?.invoke(true); pendingComplete = null
    }
    fun cancelCreation() = submit {
        mutable.update { it.copy(pending = null, problem = problemAfterSuccess(it)) }
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
        val wasPlaying = desiredPlayIntent()
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
    fun selectEntry(id: String) = submit {
        capture()
        val active = state.value.library.queues.firstOrNull { queue -> queue.entries.any { it.id == id } } ?: return@submit
        val selected = active.copy(currentEntryId = id, positionMs = 0, lastActivatedAt = nextActivation(),
            journey = journey(active).select(id, active.entries.map { it.id }))
        val next = state.value.library.update(selected).copy(activeId = active.id)
        saveStartingPlayback(next) {
            val attached = player
            if (attached != null && loadedQueueId == active.id && !holdsForEdit(selected) && !blocksCurrent(selected) && timeline.contains(selected)) {
                intents.propose(commandRevision, true, currentSource(selected))
                syncTimeline(seek = true)
                applyNativeIntent(attached, commandRevision, true)
            } else activate(play = true, seek = true)
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
        val queues = state.value.library.queues
        val from = queues.indexOfFirst { it.id == id }
        if (from >= 0) {
            save(state.value.library.copy(queues = OccurrenceMoves.byOffset(queues, from, offset)))
        }
    }
    fun removeQueues(ids: Set<String>, protectActive: Boolean = false) = submit {
        val wasPlaying = desiredPlayIntent()
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
    fun shuffle() = settings { it.copy(shuffle = !it.shuffle) }
    fun repeat() = settings { it.copy(advancedRepeat = null,
        repeat = QueueRepeat.entries[(it.repeat.ordinal + 1) % QueueRepeat.entries.size]) }
    fun randomize(queueId: String, complete: (Boolean) -> Unit = {}) = editQueue(queueId, complete) { queue ->
        QueueEdits.randomize(queue, queue.id == state.value.library.activeId && player?.isPlaying == true)
    }
    fun applySort(queueId: String, method: io.github.anastaci1a.localica.library.TrackSort, ascending: Boolean, complete: (Boolean) -> Unit = {}) =
        editQueue(queueId, complete) { queue -> withContext(Dispatchers.Default) { QueueEdits.sort(queue, method, ascending) } }
    private fun editQueue(queueId: String?, complete: (Boolean) -> Unit = {}, edit: suspend (ListeningQueue) -> ListeningQueue) = submit {
        try {
        capture()
        val active = state.value.library.queues.firstOrNull { it.id == (queueId ?: state.value.library.activeId) } ?: return@submit complete(false)
        save(state.value.library.update(edit(active)))
        if (active.id == state.value.library.activeId) activate(null)
        complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }
    /** Explicit full replacement, including resets/restoration. */
    fun settings(value: QueueSettings) = settings { value }
    /** Read-modify-write runs in the actor; callers capture only their intended field values. */
    fun settings(change: (QueueSettings) -> QueueSettings) = submit {
        capture()
        val value = change(state.value.settings)
        require(value.maximum == null || value.maximum > 0)
        require(value.previousThresholdSeconds in 1..60)
        changeSettings(value.copy(repeat = value.advancedRepeat?.repeat ?: value.repeat))
    }
    private suspend fun changeSettings(value: QueueSettings) {
        updateSettings(value)
        configurePreloading()
        mutable.update { it.copy(library = reconcile(it.library)) }
        syncTimeline()
        applyPlaybackModes()
        persist(state.value.library)
    }
    private fun updateSettings(value: QueueSettings) {
        preferences.write(value)
        mutable.update { it.copy(settings = value, problem = problemAfterSuccess(it)) }
    }
    private fun applyPlaybackModes(timelineChanged: Boolean = false) {
        val attached = player ?: return
        val modes = state.value.settings
        if (attached is ExoPlayer && (timelineChanged || attached.shuffleModeEnabled != modes.shuffle)) {
            // The saved journey already defines shuffle order. Native preloading must follow it exactly.
            attached.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                IntArray(attached.mediaItemCount) { it }, 0L))
        }
        val policy = modes.advancedRepeat
        (attached as? ExoPlayer)?.setPauseAtEndOfMediaItems(policy?.pausesEachSong == true)
        val recovery = readers.recovery()
        recoveryRepeatSuppressed = modes.repeat == QueueRepeat.All && recovery != RecoveryAdmission.Clear &&
            state.value.library.active?.entries?.any { recovery.blocks(documentIdentity(it.track.id)) } == true
        attached.repeatMode = when (if (policy?.pausesEachSong == true || recoveryRepeatSuppressed) QueueRepeat.Off else modes.repeat) {
            QueueRepeat.Off -> Player.REPEAT_MODE_OFF
            QueueRepeat.All -> Player.REPEAT_MODE_ALL
            QueueRepeat.One -> Player.REPEAT_MODE_ONE
        }
        attached.shuffleModeEnabled = modes.shuffle
    }
    private fun configurePreloading() {
        (player as? ExoPlayer)?.setPreloadConfiguration(if (state.value.settings.preloadNext)
            ExoPlayer.PreloadConfiguration(5_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
    }
    private fun nativePlayIntent(source: Player?) = source != null && source.playWhenReady &&
        source.playerError == null && source.playbackState != Player.STATE_ENDED

    private fun desiredPlayIntent(): Boolean = intents.choose(fallback =
        if (holdsForEdit(state.value.library.active) && editing?.suspendedPlayback == true)
            editPlayRequest ?: editing!!.resume else nativePlayIntent(player)).play

    /** Publish before enqueueing, so a Pause can supersede activation suspended inside the actor. */
    fun playbackRequested(play: Boolean) {
        val revision = intents.accept(play)
        submit(revision) { applyPlaybackRequest(revision) }
    }
    fun togglePlayback() {
        val revision = intents.toggle()
        submit(revision) { applyPlaybackRequest(revision) }
    }
    fun retryPlayback() = playbackRequested(true)

    private suspend fun applyPlaybackRequest(revision: Long) {
        intents.bindSource(revision, currentSource(state.value.library.active))
        val choice = intents.choose(revision, desiredPlayIntent())
        if (holdsForEdit(state.value.library.active)) { editPlayRequest = choice.play; return }
        val attached = player ?: return
        if (holdForRecovery(attached, state.value.library.active)) return
        if (attached.currentMediaItem == null) {
            activate(choice.play, recordIntent = false)
            return
        }
        if (choice.play) {
            if (attached.playbackState == Player.STATE_ENDED) attached.seekTo(0)
            intents.preparing { pauseRequested ->
                if (pauseRequested) attached.pause()
                if (intents.choose(revision, choice.play).play &&
                    (attached.playbackState == Player.STATE_IDLE || attached.playerError != null)) attached.prepare()
            }
        }
        applyNativeIntent(attached, revision, choice.play)
        capture()
    }

    private fun applyNativeIntent(attached: Player, revision: Long? = null, fallback: Boolean) {
        if (holdForRecovery(attached, state.value.library.active)) return
        intents.apply(revision, fallback) { attached.playWhenReady = it }
    }
    fun seek(positionMs: Long) = submit {
        player?.let { if (it.duration != C.TIME_UNSET && it.duration > 0) it.seekTo(positionMs.coerceIn(0, it.duration)) }
        capture()
    }
    /** Artwork and decoder preparation follow the same durable visit sequence. */
    fun artworkNeighbors(): List<String?> {
        val queue = state.value.library.active ?: return emptyList()
        return listOf(queue.journey?.next, queue.journey?.previous).map { visit ->
            queue.entries.firstOrNull { it.id == visit?.entryId }?.track?.uri
        }
    }
    fun next() = submit {
        capture()
        state.value.library.active?.journey?.next?.let { moveTo(it) }
    }
    /** A gesture commits the visit it previewed; autoplay or changed modes may invalidate it. */
    fun swipeTo(token: String, complete: (Boolean) -> Unit) = submit {
        try {
            capture()
            val journey = state.value.library.active?.journey
            val visit = listOfNotNull(journey?.previous, journey?.next).firstOrNull { it.token == token }
            if (visit == null) complete(false)
            else { moveTo(visit); complete(true) }
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun previous(alwaysSong: Boolean = false) = submit {
        capture()
        player?.let { attached ->
            if (attached.currentMediaItem != null) {
                val previous = state.value.library.active?.journey?.previous
                if ((!alwaysSong && state.value.settings.restartOnPrevious(attached.currentPosition)) || previous == null) {
                    if (!alwaysSong) attached.seekTo(0)
                } else moveTo(previous)
            } else state.value.library.active?.journey?.previous?.let { moveTo(it) }
        }
        capture()
    }
    private suspend fun moveTo(visit: QueueVisit) {
        val source = state.value
        val queue = source.library.active ?: return
        val play = desiredPlayIntent()
        val ids = queue.entries.map { it.id }
        val arrived = journey(queue, ids).arrive(visit.token, ids)
        val next = queue.copy(journey = arrived, currentEntryId = arrived.current?.entryId, positionMs = 0)
        // Arrival already normalized this active journey. Inactive/restored queues still need their usual repair.
        val prepared = PreparedPlayback(source, source.library.copy(queues = source.library.queues.map {
            if (it.id == queue.id) next else reconcileQueue(it)
        }))
        savePreparedPlayback(prepared) {
            if (timeline.contains(next)) syncTimeline(seek = true)
            else activate(play, seek = true, recordIntent = false)
        }
    }

    fun dismissProblem() { mutable.update { it.copy(problem = null) } }
    private class EditPlayback(val trackId: String, val recoveryRevision: Long? = null) {
        val abandoned = AtomicBoolean(false)
        var entryId: String? = null
        var queueId: String? = null
        var resume = false
        var suspendedPlayback = false
        @Volatile var readers: NativeReaders.Block? = null
            private set
        fun blockReaders(registry: NativeReaders): Boolean = synchronized(this) {
            if (abandoned.get()) return@synchronized false
            readers = registry.block(trackId)
            true
        }
        fun abandon() = synchronized(this) {
            abandoned.set(true)
            readers?.release()
        }
    }

    private fun holdsForEdit(queue: ListeningQueue?) =
        editing?.let { edit -> queue?.entries?.any { documentIdentity(it.track.id) == edit.trackId } == true } == true

    private fun currentSource(queue: ListeningQueue?): String? = queue?.entries
        ?.firstOrNull { it.id == queue.currentEntryId }?.track?.id?.let(::documentIdentity)
    private fun blocksSource(identity: String): Boolean {
        val recovery = readers.recovery()
        return recovery != RecoveryAdmission.Clear && recovery.blocks(documentIdentity(identity))
    }
    private fun blocksCurrent(queue: ListeningQueue) = readers.recovery() != RecoveryAdmission.Clear &&
        currentSource(queue)?.let(::blocksSource) == true
    private fun blocksVisit(queue: ListeningQueue, visit: QueueVisit?) = queue.entries
        .firstOrNull { it.id == visit?.entryId }?.track?.id?.let(::blocksSource) == true

    private fun reportProblem(message: String) {
        // A transient error never inherits recovery ownership, even if its text is identical.
        recoveryProblem = null
        mutable.update { it.copy(problem = message) }
    }
    private fun problemAfterSuccess(current: QueueUiState, next: QueueLibrary = current.library): String? {
        val notice = recoveryProblem ?: return null
        val active = next.active ?: return null
        // Preserve only the displayed notice: dismissal must not resurrect it on metadata saves.
        return notice.message.takeIf { current.problem == it && active.id == notice.queueId &&
            active.currentEntryId == notice.entryId && readers.recovery().blocks(notice.blockedSource) }
    }
    private fun clearInapplicableRecoveryProblem() {
        val notice = recoveryProblem ?: return
        if (problemAfterSuccess(state.value) != null) return
        mutable.update { if (it.problem == notice.message) it.copy(problem = null) else it }
        recoveryProblem = null
    }
    private fun reportRecovery() {
        val recovery = readers.recovery()
        val message = recovery.explanation()
        if (message.isEmpty()) return
        val queue = state.value.library.active ?: return
        // At a clipped future boundary the current song is safe; retain the actual blocked source.
        val source = (recovery as? RecoveryAdmission.Pending)?.identity ?: currentSource(queue) ?: return
        recoveryProblem = RecoveryProblem(message, queue.id, queue.currentEntryId, source)
        mutable.update { it.copy(problem = message) }
    }
    private fun holdForRecovery(attached: Player, queue: ListeningQueue?): Boolean {
        val recovery = readers.recovery()
        if (recovery == RecoveryAdmission.Clear) return false
        val source = currentSource(queue) ?: return false
        if (!recovery.blocks(source)) return false
        if (recovery != RecoveryAdmission.Checking) {
            // A passive old activation must not bind a newer request intended for a queued switch.
            intents.discardForRecovery(source, intents.currentRevision(), bindUnassigned = false)
        }
        recoveryHeld = requireNotNull(queue).id to queue.currentEntryId
        syncingTimeline = true
        try {
            if (attached.mediaItemCount > 0 || attached.playbackState != Player.STATE_IDLE || attached.playWhenReady) {
                attached.pause(); attached.stop(); attached.clearMediaItems()
            }
            loadedQueueId = null
        } finally { syncingTimeline = false }
        reportRecovery()
        return true
    }
    private suspend fun refreshRecoveryPlayback() {
        val recovery = readers.recovery()
        if (recovery == observedRecovery) return
        observedRecovery = recovery
        val attached = player ?: return
        val queue = state.value.library.active ?: return
        if (holdsForEdit(queue)) return
        if (holdForRecovery(attached, queue)) return
        clearInapplicableRecoveryProblem()
        val held = recoveryHeld
        if (held == (queue.id to queue.currentEntryId)) {
            recoveryHeld = null
            activate(false, seek = true, recordIntent = false)
        } else syncTimeline()
    }

    /** A later queue switch or service attachment must not prepare the temporarily blocked source. */
    private fun holdForEdit(attached: Player, queue: ListeningQueue?, play: Boolean? = null): Boolean {
        val token = editing ?: return false
        if (!holdsForEdit(queue)) return false
        if (!token.suspendedPlayback) {
            token.queueId = queue?.id
            token.entryId = queue?.currentEntryId
            // Until this first hold, native/pending intent is authoritative, not the empty token.
            token.resume = if (token.recoveryRevision != null && currentSource(queue) == token.trackId)
                intents.choose(token.recoveryRevision, fallback = false).play else desiredPlayIntent()
            token.suspendedPlayback = true
        }
        if (play != null) editPlayRequest = play
        if (attached.mediaItemCount != 0) {
            attached.pause(); attached.stop(); attached.clearMediaItems()
        }
        loadedQueueId = null
        return true
    }

    /** Own the request before suspending: cancellation cannot discard an acquired playback lease. */
    suspend fun <T> withFileEdit(trackId: String, action: suspend () -> T): T =
        withFileScope(trackId, recoveryRevision = null, action = action)

    /** Capture the user's Recover acceptance before the library/journal or actor can suspend. */
    internal fun recoveryRequested(): PlaybackIntents.RecoveryRequest =
        intents.recoveryRequested((readers.recovery() as? RecoveryAdmission.Pending)?.identity)

    internal fun finishRecovery(request: PlaybackIntents.RecoveryRequest) {
        val recovery = readers.recovery()
        intents.finishRecovery(request, recovery::blocks)
    }

    suspend fun <T> withFileRecovery(trackId: String, action: suspend () -> T): T {
        val request = recoveryRequested()
        try { return withFileRecovery(trackId, request, action) }
        finally { finishRecovery(request) }
    }

    internal suspend fun <T> withFileRecovery(trackId: String, request: PlaybackIntents.RecoveryRequest,
        action: suspend () -> T): T {
        // Failed duplicate/foreign claims do not own this operation's registration or cleanup.
        val revision = intents.claimRecovery(request, documentIdentity(trackId))
        try { return withFileScope(trackId, recoveryRevision = revision, action = action) }
        finally { finishRecovery(request) }
    }

    private suspend fun <T> withFileScope(trackId: String, recoveryRevision: Long?, action: suspend () -> T): T {
        val token = EditPlayback(documentIdentity(trackId), recoveryRevision)
        var failure: Throwable? = null
        try {
            withTimeout(15000) {
                editCommand(onCancellation = { token.abandoned.set(true) }) {
                    if (token.abandoned.get()) return@editCommand
                    check(editing == null) { "Another song edit is still releasing playback. Try again." }
                    capture()
                    // Persistence in capture can outlive the editor's timeout.
                    if (token.abandoned.get()) return@editCommand
                    // Registration precedes every native open. Any open that raced during capture
                    // is included by this atomic block; later opens are rejected without provider IO.
                    if (!token.blockReaders(readers)) return@editCommand
                    editing = token
                    token.recoveryRevision?.let { cutoff ->
                        intents.discardForRecovery(token.trackId, cutoff,
                            bindUnassigned = currentSource(state.value.library.active) == token.trackId)
                    }
                    editPlayRequest = null
                    player?.let { holdForEdit(it, state.value.library.active) }
                }
                // Never await the loading thread from the command actor: it must remain able to
                // process detach/attach and newer transport while native close finishes.
                token.readers?.awaitClosed()
            }
            currentCoroutineContext().ensureActive()
            return action()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            // Byte access has finished (including the writer's cancellation cleanup), or never
            // began. Release admission even if the actor is dead or its cleanup ack times out.
            token.abandon()
            try {
                withContext(NonCancellable) {
                    // A timeout bounds the caller's wait, not the queued release's lifetime.
                    withTimeout(15000) { editCommand(alwaysRun = true) { endFileEdit(token) } }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun endFileEdit(token: EditPlayback) {
        if (editing !== token) return
        val affected = holdsForEdit(state.value.library.active)
        token.recoveryRevision?.let { cutoff ->
            intents.discardForRecovery(token.trackId, cutoff,
                bindUnassigned = currentSource(state.value.library.active) == token.trackId)
        }
        editing = null
        val request = editPlayRequest
        editPlayRequest = null
        // A newer unrelated queue/player keeps its own timeline and intent, including Pause.
        if (!affected || !token.suspendedPlayback) return
        val unchanged = state.value.library.activeId == token.queueId &&
            state.value.library.active?.currentEntryId == token.entryId
        val resume = request ?: (unchanged && token.resume)
        activate(resume, seek = true, recordIntent = false)
    }

    private suspend fun <T> editCommand(alwaysRun: Boolean = false, onCancellation: () -> Unit = {},
        action: suspend () -> T): T = suspendCancellableCoroutine { continuation ->
        val stopped = commandJob.invokeOnCompletion { cause ->
            continuation.cancel(CancellationException("Playback controller stopped.", cause))
        }
        continuation.invokeOnCancellation { onCancellation(); stopped.dispose() }
        val sent = commands.trySend {
            try {
                if (alwaysRun || continuation.isActive) continuation.resumeWith(Result.success(action()))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally { stopped.dispose() }
        }
        if (sent.isFailure) {
            stopped.dispose()
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Playback controller is unavailable."))
        }
    }
    fun refreshTrack(track: Track, complete: (Boolean) -> Unit) = refreshTracks(listOf(track), complete)
    fun refreshTracks(tracks: List<Track>, complete: (Boolean) -> Unit) = submit {
        val byId = tracks.associateBy { it.id }
        try {
            capture()
            save(state.value.library.copy(queues = state.value.library.queues.map { queue ->
                queue.copy(entries = queue.entries.map { entry -> byId[entry.track.id]?.let { entry.copy(track = it) } ?: entry })
            }))
            activate(null); complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }

    /** Used by the existing clear action; retained queues are intentionally kept. */
    fun clearPlayback() = submit(intents.accept(false)) {
        capture()
        save(state.value.library.copy(activeId = null))
        activate(false)
    }

    private fun journey(queue: ListeningQueue, ids: List<String> = queue.entries.map { it.id }): QueueJourney {
        val modes = JourneyModes.from(state.value.settings)
        return queue.journey?.reconcile(ids, queue.currentEntryId, modes)
            ?: QueueJourney.start(ids, queue.currentEntryId, modes)
    }
    private fun reconcileQueue(queue: ListeningQueue) =
        if (queue.journey == null) queue else queue.copy(journey = journey(queue))
    private fun reconcile(value: QueueLibrary) = value.copy(queues = value.queues.map(::reconcileQueue))
    private fun capturedLibrary(source: Player?): QueueLibrary {
        var result = state.value.library
        while (arrivals.isNotEmpty()) {
            val arrival = arrivals.removeFirst()
            val queue = result.queues.firstOrNull { it.id == arrival.queueId } ?: continue
            if (readers.recovery() != RecoveryAdmission.Clear &&
                blocksVisit(queue, queue.journey?.window()?.firstOrNull { it.token == arrival.token })) continue
            val arrived = queue.journey?.arrive(arrival.token, queue.entries.map { it.id }) ?: continue
            result = result.update(queue.copy(journey = arrived, currentEntryId = arrived.current?.entryId, positionMs = 0))
        }
        val queue = result.active ?: return result
        val item = source?.currentMediaItem ?: return result
        if (item.listeningQueue != queue.id || queue.entries.none { it.id == item.mediaId }) return result
        val arrived = queue.journey?.arrive(item.queueVisit ?: return result, queue.entries.map { it.id }) ?: return result
        // A removed/branched visit must never overwrite a newer explicit selection.
        if (arrived.current?.token != item.queueVisit) return result
        return result.update(queue.copy(journey = arrived, currentEntryId = arrived.current?.entryId,
            positionMs = source.currentPosition.coerceAtLeast(0)))
    }
    private suspend fun capture() {
        mutable.update { it.copy(library = capturedLibrary(player)) }
        player?.let { attached ->
            val modes = state.value.settings
            val repeat = when (attached.repeatMode) { Player.REPEAT_MODE_ALL -> QueueRepeat.All
                Player.REPEAT_MODE_ONE -> QueueRepeat.One; else -> QueueRepeat.Off }
            if (state.value.library.active?.let(::blocksCurrent) != true &&
                (modes.shuffle != attached.shuffleModeEnabled || (modes.advancedRepeat == null && !recoveryRepeatSuppressed && modes.repeat != repeat))) {
                updateSettings(modes.copy(shuffle = attached.shuffleModeEnabled,
                    repeat = if (modes.advancedRepeat == null && !recoveryRepeatSuppressed) repeat else modes.repeat))
                mutable.update { it.copy(library = reconcile(it.library)) }
            }
            syncTimeline()
        }
        persist(state.value.library)
    }
    private suspend fun save(next: QueueLibrary) {
        val reconciled = reconcile(next)
        persist(reconciled)
        mutable.update { it.copy(library = reconciled, problem = problemAfterSuccess(it, reconciled)) }
    }
    private suspend fun persist(next: QueueLibrary) {
        if (persistedLibrary != next) {
            withContext(Dispatchers.IO) { dao.save(persistedLibrary, next) }
            persistedLibrary = next
        }
    }
    private suspend fun saveStartingPlayback(next: QueueLibrary, start: suspend () -> Unit) {
        savePreparedPlayback(PreparedPlayback(state.value, reconcile(next)), start)
    }
    private suspend fun savePreparedPlayback(prepared: PreparedPlayback, start: suspend () -> Unit) {
        check(state.value.library === prepared.source.library && state.value.settings === prepared.source.settings) {
            "Playback preparation no longer matches the queue state"
        }
        mutable.update { it.copy(library = prepared.library, problem = problemAfterSuccess(it, prepared.library)) }
        // Audio starts before persistence; failed writes remain retryable from the durable snapshot.
        try { start() } finally { persist(state.value.library) }
    }
    private fun syncTimeline(seek: Boolean = false) {
        val attached = player ?: return
        val queue = state.value.library.active ?: return
        if (holdForEdit(attached, queue) || holdForRecovery(attached, queue) || queue.entries.isEmpty() || !timeline.contains(queue)) return
        val recovery = readers.recovery()
        // Normal playback retains its existing bounded-window path; do not scan/parse every song.
        val window = if (recovery == RecoveryAdmission.Clear) requireNotNull(queue.journey).window()
            else recoveryWindow(queue) { recovery.blocks(documentIdentity(it)) }
        if (window.isEmpty()) return
        intents.preparing { pauseRequested ->
            // Keep Pause pending through the suspended operation's final play decision.
            if (pauseRequested) attached.pause()
            syncingTimeline = true
            try {
                val changed = timeline.sync(attached, queue, seek, loadedQueueId != queue.id || attached.mediaItemCount == 0, window)
                loadedQueueId = queue.id
                applyPlaybackModes(changed)
            } finally { syncingTimeline = false }
        }
    }
    private suspend fun activate(play: Boolean?, seek: Boolean = false, refreshLocations: Boolean = true,
        recordIntent: Boolean = true) {
        if (play != null && recordIntent) intents.propose(commandRevision, play, currentSource(state.value.library.active))
        val revision = commandRevision.takeIf { play != null && recordIntent }
        val attached = player ?: return
        val queue = state.value.library.active
        val heldRequest = if (play != null || intents.pending() != null)
            intents.choose(revision, play ?: desiredPlayIntent()).play else null
        if (holdForEdit(attached, queue, heldRequest) || holdForRecovery(attached, queue)) return
        recoveryHeld = null
        clearInapplicableRecoveryProblem()
        if (queue == null || queue.entries.isEmpty()) {
            loadedQueueId = queue?.id
            attached.stop(); attached.clearMediaItems(); applyPlaybackModes(timelineChanged = true)
            return
        }
        val items = withContext(Dispatchers.IO) {
            val currentLibrary = if (refreshLocations) library.snapshots.first().tracks.associateBy { it.id } else emptyMap()
            val tracks = library.playableTracks(queue.entries.map { currentLibrary[it.track.id] ?: it.track })
            queue.entries.zip(tracks) { entry, track -> queueMediaItem(entry.id, track, entry.sourcePlaylistId) }
        }
        if (player !== attached) return
        // An ordinary edit may resolve locations while the decoder advances to its prepared next song.
        if (!seek && loadedQueueId == queue.id) mutable.update { it.copy(library = capturedLibrary(attached)) }
        val latest = state.value.library.active ?: return
        mutable.update { it.copy(library = it.library.update(latest.copy(journey = journey(latest)))) }
        timeline.resolve(queue.id, items)
        syncTimeline(seek)
        if (play != null || intents.pending() != null) applyNativeIntent(attached, revision, play ?: nativePlayIntent(attached))
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
