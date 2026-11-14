package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.OccurrenceMoves
import io.github.anastaci1a.localica.library.OrderCommit
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
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** In-process command gateway. Only the service attaches a player; every mutation is serialized here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueController(private val dao: QueueDao, private val preferences: QueuePreferences,
                      private val library: LibraryRepository,
                      private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                      private val readers: NativeReaders = NativeReaders(),
                      private val sortSnapshot: suspend () -> io.github.anastaci1a.localica.library.LibrarySnapshot = { library.sortSnapshots.first() },
                      private val mediaItemFactory: (String, Track, String?) -> MediaItem = ::queueMediaItem,
                      diagnoseOrders: Boolean = false,
                      private val listening: io.github.anastaci1a.localica.statistics.ListeningEditLifecycle? = null) {
    /** Debug diagnostics only; the sink runs off the actor after its terminal callback. */
    internal var orderTimingObserver: ((String, QueueOrderTiming.Report) -> Unit)? = if (diagnoseOrders) {
        { operation, report -> android.util.Log.i("QueueOrderTiming", "operation=$operation $report"); Unit }
    } else null
    private fun reportOrderTiming(operation: String, timing: QueueOrderTiming?, outcome: QueueOrderTiming.Outcome) {
        val report = timing?.finish(outcome) ?: return
        val observer = orderTimingObserver ?: return
        scope.launch(Dispatchers.IO) {
            try { observer(operation, report) }
            catch (_: Exception) { android.util.Log.w("QueueOrderTiming", "Diagnostic sink failed") }
        }
    }
    private data class QueuedCommand(val cursorOnly: Boolean, val reject: (Exception) -> Unit = {},
        val transportDuringSave: Boolean = cursorOnly,
        val action: suspend () -> Unit)
    private val commands = Channel<QueuedCommand>(Channel.UNLIMITED)
    // The first structural command encountered during an activation wait remains the FIFO head.
    private var commandAfterActivation: QueuedCommand? = null
    // Only the actor defers writes: bounded cursor batches or eligible transport during an activation wait.
    private var deferCursorPersistence = false
    private class Checkpoint(var library: QueueLibrary, val receipt: CompletableDeferred<Unit>? = null)
    private val checkpointLock = Any()
    // At most one unsent-to-disk cursor snapshot is retained, even during a stalled write.
    private var pendingCursorCheckpoint: Checkpoint? = null
    private val checkpoints = Channel<Checkpoint>(Channel.UNLIMITED)
    private val persistenceJob: Job
    private val commandJob: Job
    private val intents = PlaybackIntents()
    private var commandRevision = 0L
    private val mutable = MutableStateFlow(QueueUiState(settings = preferences.read()))
    val state = mutable.asStateFlow()
    private var player: Player? = null
    private val timeline = QueueTimeline()
    private data class ResolvedOccurrence(val track: Track, val sourcePlaylistId: String?, val item: MediaItem)
    private var resolved = emptyMap<String, ResolvedOccurrence>()
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
            if (ended) enqueueSongEnd(requestedAtEnd) else submitCursor { capture() }
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
            submitCursor { capture() }
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
            // Historical and scheduled visits may cross labels while remaining in the active traversal.
            val withinTraversal = next != null && next.cycle <= journey.cycle
            if (policy.songEnd == SongEnd.Stop && withinTraversal) return@submit
            if (policy.songEnd == SongEnd.NextPaused && withinTraversal) {
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
        persistenceJob = scope.launch {
            var pending: Checkpoint? = null
            try {
                while (isActive) {
                    var checkpoint = pending ?: checkpoints.receiveCatching().getOrNull() ?: break
                    pending = null
                    // Adjacent cursor snapshots may supersede each other, never an awaited barrier.
                    if (checkpoint.receipt == null) while (true) {
                        val following = checkpoints.tryReceive().getOrNull() ?: break
                        if (following.receipt != null) { pending = following; break }
                        checkpoint = following
                    }
                    val target = synchronized(checkpointLock) {
                        if (pendingCursorCheckpoint === checkpoint) pendingCursorCheckpoint = null
                        checkpoint.library
                    }
                    val receipt = checkpoint.receipt
                    try {
                        if (persistedLibrary != target) {
                            withContext(Dispatchers.IO) { dao.save(persistedLibrary, target) }
                            persistedLibrary = target
                        }
                        receipt?.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        receipt?.cancel(cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        if (receipt != null) receipt.completeExceptionally(failure)
                        else if (state.value.library == target && recoveryProblem == null) {
                            // A delayed cursor failure must not erase a newer queue/recovery notice.
                            reportProblem("Couldn’t save this queue change. Try again.")
                        }
                    }
                }
            } finally {
                val stopped = CancellationException("Queue persistence stopped.")
                checkpoints.close(stopped)
                synchronized(checkpointLock) { pendingCursorCheckpoint = null }
                pending?.receipt?.cancel(stopped)
                while (true) (checkpoints.tryReceive().getOrNull() ?: break).receipt?.cancel(stopped)
            }
        }
        commandJob = scope.launch {
            var restoreFailure: Exception? = null
            try {
                val saved = dao.read()
                persistedLibrary = saved
                preferences.migratePlaybackModes(saved.active)
                mutable.update { it.copy(library = saved, settings = preferences.read(), loading = false) }; restored = true
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                restoreFailure = failure
                mutable.update { it.copy(loading = false, problem = "Couldn’t restore queues. Reopen the app to retry.") }
            }
            var pending: QueuedCommand? = null
            while (isActive) {
                var current = pending ?: commandAfterActivation?.also { commandAfterActivation = null }
                    ?: commands.receiveCatching().getOrNull() ?: break
                pending = null
                var cursorBatch = false
                try {
                    var count = 0
                    while (true) {
                        // Finish each Media3 event batch before touching its timeline again.
                        yield()
                        deferCursorPersistence = current.cursorOnly
                        cursorBatch = cursorBatch || current.cursorOnly
                        try {
                            if (restored) current.action()
                            else {
                                reportProblem("Queues are unavailable. Reopen the app to retry restoring them.")
                                current.reject(IllegalStateException("Queues could not be restored.", restoreFailure))
                            }
                        } finally { deferCursorPersistence = false }
                        // Never conflate commands: Next/Previous (including restart semantics) run in order.
                        // Only redundant disk snapshots are combined. Bound the batch so sustained input
                        // cannot indefinitely postpone checkpoint submission; structural saves still await durability.
                        if (!current.cursorOnly || ++count >= 32) break
                        val following = commands.tryReceive().getOrNull() ?: break
                        if (!following.cursorOnly) { pending = following; break }
                        current = following
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportProblem("Couldn’t save this queue change. Try again.") }
                finally {
                    deferCursorPersistence = false
                    if (cursorBatch && restored && isActive &&
                        !checkpointCursor(state.value.library)) {
                        reportProblem("Couldn’t save this queue change. Try again.")
                    }
                }
            }
        }
        commandJob.invokeOnCompletion { commands.close(); checkpoints.close() }
        scope.launch { readers.recoveryChanges.collect {
            // Notifications may coalesce or arrive after another publication. Read the authority.
            submit { refreshRecoveryPlayback() }
        } }
        scope.launch { while (isActive) { delay(5000); if (restored) submitCursor { capture() } } }
    }

    private fun submit(revision: Long = intents.accept(), reject: (Exception) -> Unit = {},
        transportDuringSave: Boolean = false, command: suspend () -> Unit) {
        if (!enqueue(revision, reject, transportDuringSave, command)) reject(IllegalStateException("Playback controller is unavailable."))
    }
    private fun submitCursor(command: suspend () -> Unit) {
        val revision = intents.accept()
        commands.trySend(QueuedCommand(cursorOnly = true) { commandRevision = revision; command() })
    }
    private fun enqueue(revision: Long = intents.accept(), reject: (Exception) -> Unit = {},
        transportDuringSave: Boolean = false, command: suspend () -> Unit): Boolean =
        commands.trySend(QueuedCommand(cursorOnly = false, reject = reject, transportDuringSave = transportDuringSave) {
            commandRevision = revision; command()
        }).isSuccess
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
    /** Capture before release; the receipt completes only after the final ordered disk write. */
    fun detach(attached: Player): Deferred<Unit> {
        generation++
        // Take the final cursor before MediaSessionService releases the player.
        val item = attached.currentMediaItem
        val queueId = item?.listeningQueue
        val visit = item?.queueVisit
        val position = attached.currentPosition.coerceAtLeast(0)
        attached.removeListener(listener)
        if (player === attached) { player = null; intents.observe(false) }
        val revision = intents.accept()
        // Enqueue immediately, even if the caller uses a different dispatcher.
        return scope.async(start = CoroutineStart.UNDISPATCHED) { editCommand(alwaysRun = true) {
            commandRevision = revision
            // Fold arrivals after any pending edit commits, so its older snapshot cannot erase them.
            var captured = capturedLibrary(null)
            val queue = captured.queues.firstOrNull { it.id == queueId }
            if (queue != null && queue.journey?.current?.token == visit) {
                captured = captured.update(queue.copy(positionMs = position))
            }
            mutable.update { it.copy(library = captured) }
            persist(captured)
        } }
    }

    fun tap(tracks: List<Track>, selectedId: String) = tap(tracks, tracks.indexOfFirst { it.id == selectedId })
    fun tap(tracks: List<Track>, selectedIndex: Int, view: SongListView = SongListView.Songs, sourceName: String = "All Songs", playlistOrigins: List<String?> = emptyList()) = submit {
        perform(tracks, selectedIndex, state.value.settings.tapFor(view), batch = false, sourceName = sourceName, playlistOrigins = playlistOrigins)
    }
    /** Explicit selection actions bypass all tap preferences and preserve displayed occurrence order. */
    fun act(tracks: List<Track>, action: SongTap, playlistOrigins: List<String?> = emptyList(), complete: (Boolean) -> Unit = {}) = submit(reject = { complete(false) }) {
        try {
            val done = perform(tracks, 0, action, batch = true, playlistOrigins = playlistOrigins)
            if (done) complete(true) else if (state.value.pending != null) pendingComplete = complete else complete(false)
        } catch (failure: Exception) { complete(false); throw failure }
    }

    fun appendTo(id: String, rows: List<io.github.anastaci1a.localica.library.SongRow>, complete: (Boolean) -> Unit = {}) = submit(reject = { complete(false) }) {
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
            if (reuse(tracks, selectedIndex, expand, sourceName, playlistOrigins)) return true
            if (!QueueEdits.canCreate(state.value.library, state.value.settings.maximum, emptySet())) {
                mutable.update { it.copy(pending = PendingQueue(tracks.toList(), selectedIndex, QueueEdits.oldestInactive(it.library), expand, sourceName, playlistOrigins)) }
                return false
            } else create(tracks, selectedIndex, emptySet(), expand, sourceName, playlistOrigins)
        } else {
            var inserted = QueueEdits.insert(current, if (batch) tracks else listOf(selected), action, if (batch) playlistOrigins else listOf(playlistOrigins.getOrNull(selectedIndex)))
            if (action == SongTap.PlayNow) inserted = inserted.copy(journey = journey(current).select(
                requireNotNull(inserted.currentEntryId), inserted.entries.map { it.id }))
            if (action == SongTap.PlayNext) {
                val existing = current.entries.mapTo(hashSetOf()) { it.id }
                val added = inserted.entries.filterNot { it.id in existing }.map { it.id }
                val ids = inserted.entries.map { it.id }
                val unanchored = current.currentEntryId == null
                val anchor = if (unanchored) QueueJourney.start(ids.filter { it in existing || it == inserted.currentEntryId },
                    inserted.currentEntryId, JourneyModes.from(state.value.settings)) else journey(current)
                inserted = inserted.copy(journey = anchor.playNext(if (unanchored) added.drop(1) else added, ids))
            }
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
        if (reuse(pending.tracks, pending.selectedIndex, pending.expandPlayer, pending.sourceName, pending.playlistOrigins)) {
            pendingComplete?.invoke(true); pendingComplete = null
            return@submit
        }
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
    private suspend fun reuse(tracks: List<Track>, selectedIndex: Int, expandPlayer: Boolean,
        sourceName: String, playlistOrigins: List<String?>): Boolean {
        val queue = QueueEdits.equivalent(state.value.library, tracks, sourceName, playlistOrigins) ?: return false
        selectEntry(queue, queue.entries[selectedIndex].id, expandPlayer)
        mutable.update { it.copy(pending = null) }
        return true
    }
    private suspend fun create(tracks: List<Track>, selectedIndex: Int, removals: Set<String>, expandPlayer: Boolean, sourceName: String, playlistOrigins: List<String?>) {
        // A library-sized tap mints one random occurrence ID per song (SecureRandom); keep that
        // pure construction off Main. Queue state is read only after it returns.
        val created = withContext(Dispatchers.Default) { QueueEdits.create(tracks, selectedIndex, sourceName, playlistOrigins) }
        val queue = created.copy(lastActivatedAt = nextActivation())
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
        selectEntry(active, id, expandPlayer = true)
    }
    private suspend fun selectEntry(active: ListeningQueue, id: String, expandPlayer: Boolean) {
        val selected = active.copy(currentEntryId = id, positionMs = 0, lastActivatedAt = nextActivation(),
            journey = journey(active).select(id, active.entries.map { it.id }))
        val next = state.value.library.update(selected).copy(activeId = active.id)
        saveStartingPlayback(next) {
            val attached = player
            if (attached != null && loadedQueueId == active.id && !holdsForEdit(selected) && !blocksCurrent(selected) && timeline.queueId == selected.id) {
                intents.propose(commandRevision, true, currentSource(selected))
                syncTimeline(seek = true)
                applyNativeIntent(attached, commandRevision, true)
            } else activate(play = true, seek = true)
        }
        mutable.update { it.copy(browsedId = active.id) }
        if (expandPlayer) requestPlayer()
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
    /** Resolve a drag's identities at execution time; null appends to the latest queue order. */
    fun moveQueueBefore(id: String, beforeId: String?, complete: (Boolean) -> Unit = {}) = submit(reject = { complete(false) }) {
        val before = state.value.library
        if (before.queues.none { it.id == id } ||
            beforeId != null && before.queues.none { it.id == beforeId }) return@submit complete(false)
        if (id == beforeId) return@submit complete(true)
        val reordered = OccurrenceMoves.before(before.queues, setOf(id), beforeId) { it.id }
        try {
            if (reordered != before.queues) {
                val next = before.copy(queues = reordered)
                // Only ordering changes: do not reconcile journeys or touch the native timeline.
                persist(next)
                mutable.update { it.copy(library = next, problem = problemAfterSuccess(it, next)) }
            }
        } catch (failure: Exception) { complete(false); throw failure }
        complete(true)
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
    fun removeEntries(ids: Set<String>, queueId: String? = null, complete: (Boolean) -> Unit = {}) = submit(reject = { complete(false) }) {
        try {
            capture()
            val active = state.value.library.queues.firstOrNull { it.id == (queueId ?: state.value.library.activeId) } ?: return@submit complete(false)
            val edited = active.entries.filter { it.id in ids }.fold(active) { queue, entry -> QueueEdits.remove(queue, entry.id) }
            save(state.value.library.update(edited)); if (active.id == state.value.library.activeId) activate(null); complete(true)
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun moveEntry(id: String, offset: Int, queueId: String? = null, complete: (Boolean) -> Unit = {}) = editQueue(queueId, complete) { QueueEdits.move(it, id, offset) }
    fun moveBefore(queueId: String, entries: Set<String>, beforeId: String?, complete: (Boolean) -> Unit = {}) =
        moveBefore(queueId, entries, beforeId, null, complete)
    internal fun moveBefore(queueId: String, entries: Set<String>, beforeId: String?, expectedOrder: List<String>?,
        complete: (Boolean) -> Unit) = editQueue(queueId, complete) {
        require(expectedOrder == null || it.entries.map { entry -> entry.id } == expectedOrder) {
            "The queue changed. Try dragging again."
        }
        QueueEdits.moveBefore(it, entries, beforeId)
    }
    fun shuffle() = settings { it.copy(shuffle = !it.shuffle) }
    fun repeat() = settings { it.copy(advancedRepeat = null,
        repeat = QueueRepeat.entries[(it.repeat.ordinal + 1) % QueueRepeat.entries.size]) }
    fun randomize(queueId: String, complete: (Boolean) -> Unit = {}) =
        randomize(queueId, kotlin.random.Random.Default, complete)
    internal fun randomize(queueId: String, random: kotlin.random.Random, complete: (Boolean) -> Unit) =
        randomizeOrder(queueId, random) { complete(it != null) }
    internal fun randomizeOrder(queueId: String, complete: (OrderCommit?) -> Unit) =
        randomizeOrder(queueId, kotlin.random.Random.Default, complete)
    internal fun randomizeOrder(queueId: String, random: kotlin.random.Random, complete: (OrderCommit?) -> Unit) =
        editOrder(queueId, complete, "randomize") { queue ->
            QueueEdits.randomize(queue, queue.id == state.value.library.activeId, random)
        }
    fun reverse(queueId: String, complete: (Boolean) -> Unit = {}) =
        reverseOrder(queueId) { complete(it != null) }
    internal fun reverseOrder(queueId: String, complete: (OrderCommit?) -> Unit) =
        editOrder(queueId, complete, "reverse") { queue -> withContext(Dispatchers.Default) { QueueEdits.reverse(queue) } }
    fun applySort(queueId: String, method: io.github.anastaci1a.localica.library.TrackSort, ascending: Boolean, complete: (Boolean) -> Unit = {}) =
        applySortOrder(queueId, method, ascending) { complete(it != null) }
    internal fun applySortOrder(queueId: String, method: io.github.anastaci1a.localica.library.TrackSort, ascending: Boolean, complete: (OrderCommit?) -> Unit) =
        editOrder(queueId, complete, "sort") { queue ->
            val snapshot = if (method == io.github.anastaci1a.localica.library.TrackSort.Manual) null else sortSnapshot()
            withContext(Dispatchers.Default) { QueueEdits.sort(queue, method, ascending, snapshot) }
        }
    fun revertOrder(queueId: String, checkpoint: io.github.anastaci1a.localica.library.OrderCheckpoint, complete: (Boolean) -> Unit = {}) =
        revertOrderWithCommit(queueId, checkpoint) { complete(it != null) }
    internal fun revertOrderWithCommit(queueId: String, checkpoint: io.github.anastaci1a.localica.library.OrderCheckpoint, complete: (OrderCommit?) -> Unit) =
        editOrder(queueId, complete, "revert") { queue -> withContext(Dispatchers.Default) { QueueEdits.revertOrder(queue, checkpoint) } }
    private fun editOrder(queueId: String, complete: (OrderCommit?) -> Unit, operation: String,
        edit: suspend (ListeningQueue) -> ListeningQueue) {
        var before: ListeningQueue? = null
        val timing = orderTimingObserver?.let { QueueOrderTiming(System::nanoTime) }
        editQueue(queueId, orderTiming = timing, orderOperation = operation, complete = { success ->
            val library = state.value.library
            val after = library.queues.firstOrNull { it.id == queueId }
            val previous = before
            // StateFlow retains its old value for equivalent replacements. Its published
            // entries, rather than the edit's temporary list, own the completion revision.
            complete(if (success && previous != null && after != null) OrderCommit(
                revision = after.entries,
                order = after.order,
                changed = OrderCommit.hasChanged(previous.order, after.order,
                    previous.entries, after.entries) { it.id },
                currentEntryId = after.currentEntryId.takeIf { after.id == library.activeId },
            ) else null)
        }) { current -> before = current; edit(current) }
    }
    private fun editQueue(queueId: String?, complete: (Boolean) -> Unit = {},
        orderTiming: QueueOrderTiming? = null, orderOperation: String = "other",
        edit: suspend (ListeningQueue) -> ListeningQueue) = submit(reject = { complete(false) }) {
        orderTiming?.mark(QueueOrderTiming.Stage.ActorStarted)
        var outcome = QueueOrderTiming.Outcome.Failure
        try {
        capture(orderTiming)
        val active = state.value.library.queues.firstOrNull { it.id == (queueId ?: state.value.library.activeId) }
        if (active == null) {
            complete(false)
            orderTiming?.mark(QueueOrderTiming.Stage.CallbackCompleted)
            return@submit
        }
        orderTiming?.recordCounts(queueEntries = active.entries.size)
        save(state.value.library.update(edit(active)))
        orderTiming?.mark(QueueOrderTiming.Stage.DurableEditSaved)
        if (active.id == state.value.library.activeId) activate(null, orderTiming = orderTiming)
        complete(true)
        orderTiming?.mark(QueueOrderTiming.Stage.CallbackCompleted)
        outcome = QueueOrderTiming.Outcome.Success
        } catch (failure: Exception) {
            if (failure is CancellationException) outcome = QueueOrderTiming.Outcome.Cancelled
            complete(false)
            orderTiming?.mark(QueueOrderTiming.Stage.CallbackCompleted)
            throw failure
        } finally { reportOrderTiming(orderOperation, orderTiming, outcome) }
    }
    /** Explicit full replacement, including resets/restoration. */
    fun settings(value: QueueSettings) = settings { value }
    /** Read-modify-write runs in the actor; callers capture only their intended field values. */
    fun settings(change: (QueueSettings) -> QueueSettings) = submit {
        // Capture and publish the native mode change without an intervening cursor-only disk wait.
        captureNativeState()
        val value = change(state.value.settings)
        require(value.maximum == null || value.maximum > 0)
        require(value.previousThresholdSeconds in 1..60)
        changeSettings(value.copy(repeat = value.advancedRepeat?.repeat ?: value.repeat))
    }
    private suspend fun changeSettings(value: QueueSettings) {
        updateSettings(value)
        configurePreloading()
        mutable.update { it.copy(library = reconcile(it.library)) }
        applyPlaybackModes()
        syncTimeline()
        persist(state.value.library)
    }
    private fun updateSettings(value: QueueSettings) {
        preferences.write(value)
        mutable.update { it.copy(settings = value, problem = problemAfterSuccess(it)) }
    }
    private fun applyPlaybackModes(timelineChanged: Boolean = false) {
        val attached = player ?: return
        val modes = state.value.settings
        if (attached is ExoPlayer && timelineChanged) {
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
        submit(revision, transportDuringSave = true) { applyPlaybackRequest(revision) }
    }
    fun togglePlayback() {
        val revision = intents.toggle()
        submit(revision, transportDuringSave = true) { applyPlaybackRequest(revision) }
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
    fun seek(positionMs: Long) = submit(transportDuringSave = true) {
        player?.let { attached ->
            if (attached.currentMediaItem != null) {
                val target = seekTarget(positionMs, attached.duration) ?: return@let
                attached.seekTo(target)
            }
        }
        capture()
    }
    // Next can change the native item before its duration is ready. Media3 masks
    // an ordered seek until preparation completes; both command paths retain it.
    private fun seekTarget(positionMs: Long, duration: Long): Long? = when {
        duration == C.TIME_UNSET -> positionMs.coerceAtLeast(0)
        duration > 0 -> positionMs.coerceIn(0, duration)
        else -> null
    }

    /** Main-thread snapshot of the service-owned player; no command or persistence work. */
    internal fun playbackSample(): QueuePlaybackSample? = player?.let { attached ->
        attached.currentMediaItem?.let { item ->
            QueuePlaybackSample(attached.currentPosition.coerceAtLeast(0), item.queueVisit)
        }
    }

    /** UI handoff needs an applied receipt, bound to the player/visit at dispatch. */
    internal fun requestSeek(positionMs: Long, expectedVisit: String?, complete: (Boolean, QueuePlaybackSample?) -> Unit) {
        val expected = player
        val expectedGeneration = generation
        val accepted = enqueue(reject = { complete(false, playbackSample()) }, transportDuringSave = true) {
            val attached = player
            if (attached == null || attached !== expected || generation != expectedGeneration ||
                attached.currentMediaItem == null || attached.currentMediaItem?.queueVisit != expectedVisit) {
                complete(false, playbackSample())
                return@enqueue
            }
            val target = seekTarget(positionMs, attached.duration)
            if (target == null) {
                complete(false, playbackSample())
                return@enqueue
            }
            try {
                attached.seekTo(target)
            } catch (failure: Exception) {
                complete(false, playbackSample())
                throw failure
            }
            // Native position masking has taken effect now. A slow storage capture
            // cannot delay the receipt or turn command enqueueing into acceptance.
            complete(true, playbackSample())
            capture()
        }
        if (!accepted) complete(false, playbackSample())
    }

    /** Artwork and decoder preparation follow the same durable visit sequence. */
    fun artworkNeighbors(): List<String?> {
        val queue = state.value.library.active ?: return emptyList()
        return listOf(queue.journey?.next, queue.journey?.previous).map { visit ->
            queue.entries.firstOrNull { it.id == visit?.entryId }?.track?.uri
        }
    }
    fun artworkPreloadUris(): List<String> {
        val queue = state.value.library.active ?: return emptyList()
        val tracks = queue.entries.associateBy { it.id }
        return queue.journey?.artworkWindow().orEmpty().mapNotNull { tracks[it.entryId]?.track?.uri }.distinct()
    }
    fun next() = submitCursor {
        capture()
        state.value.library.active?.journey?.next?.let { moveTo(it) }
    }
    /** A gesture commits the visit it previewed; autoplay or changed modes may invalidate it. */
    fun swipeTo(token: String, complete: (Boolean) -> Unit) = submit(reject = { complete(false) }) {
        try {
            capture()
            val journey = state.value.library.active?.journey
            val visit = listOfNotNull(journey?.previous, journey?.next).firstOrNull { it.token == token }
            if (visit == null) complete(false)
            else { moveTo(visit); complete(true) }
        } catch (failure: Exception) { complete(false); throw failure }
    }
    fun previous(alwaysSong: Boolean = false) = submitCursor {
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
            if (timeline.queueId == next.id) syncTimeline(seek = true)
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
            listening?.suspendForEdit()
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
        try {
            // A newer unrelated queue/player keeps its own timeline and intent, including Pause.
            if (!affected || !token.suspendedPlayback) return
            val unchanged = state.value.library.activeId == token.queueId &&
                state.value.library.active?.currentEntryId == token.entryId
            val resume = request ?: (unchanged && token.resume)
            activate(resume, seek = true, recordIntent = false)
        } finally {
            if (token.suspendedPlayback) listening?.finishEdit(state.value.library.active?.journey?.current?.token)
        }
    }

    private suspend fun <T> editCommand(alwaysRun: Boolean = false, onCancellation: () -> Unit = {},
        action: suspend () -> T): T = suspendCancellableCoroutine { continuation ->
        val stopped = commandJob.invokeOnCompletion { cause ->
            continuation.cancel(CancellationException("Playback controller stopped.", cause))
        }
        continuation.invokeOnCancellation { onCancellation(); stopped.dispose() }
        val sent = commands.trySend(QueuedCommand(cursorOnly = false, reject = { failure ->
            stopped.dispose()
            if (continuation.isActive) continuation.resumeWithException(failure)
        }) {
            try {
                if (alwaysRun || continuation.isActive) continuation.resumeWith(Result.success(action()))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally { stopped.dispose() }
        })
        if (sent.isFailure) {
            stopped.dispose()
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Playback controller is unavailable."))
        }
    }
    fun refreshTrack(track: Track, complete: (Boolean) -> Unit) = refreshTracks(listOf(track), complete)
    fun refreshTracks(tracks: List<Track>, complete: (Boolean) -> Unit) = submit(reject = { complete(false) }) {
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
        listening?.clearTraversal()
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
            val captured = queue.captureVisit(arrival.token) { 0 } ?: continue
            if (captured !== queue) result = result.update(captured)
        }
        val queue = result.active ?: return result
        val item = source?.currentMediaItem ?: return result
        if (item.listeningQueue != queue.id || queue.entries.none { it.id == item.mediaId }) return result
        // A removed/branched visit must never overwrite a newer explicit selection.
        val captured = queue.captureVisit(item.queueVisit ?: return result) {
            source.currentPosition.coerceAtLeast(0) } ?: return result
        return if (captured === queue) result else result.update(captured)
    }
    private fun captureNativeState() {
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
        }
    }
    private suspend fun capture(orderTiming: QueueOrderTiming? = null) {
        val journey = state.value.library.active?.journey
        captureNativeState()
        if (state.value.library.active?.journey !== journey) syncTimeline()
        orderTiming?.mark(QueueOrderTiming.Stage.CursorCaptured)
        persist(state.value.library)
    }
    private suspend fun save(next: QueueLibrary) {
        val reconciled = reconcile(next)
        persist(reconciled)
        mutable.update { it.copy(library = reconciled, problem = problemAfterSuccess(it, reconciled)) }
    }
    private fun checkpointCursor(next: QueueLibrary): Boolean = synchronized(checkpointLock) {
        val pending = pendingCursorCheckpoint
        if (pending != null) {
            pending.library = next
            true
        } else {
            val checkpoint = Checkpoint(next)
            pendingCursorCheckpoint = checkpoint
            val sent = checkpoints.trySend(checkpoint).isSuccess
            if (!sent && pendingCursorCheckpoint === checkpoint) pendingCursorCheckpoint = null
            sent
        }
    }
    private suspend fun persist(next: QueueLibrary, serviceTransport: Boolean = false) {
        if (deferCursorPersistence) return
        val receipt = CompletableDeferred<Unit>()
        synchronized(checkpointLock) {
            // Never replace a cursor snapshot across an awaited structural barrier.
            pendingCursorCheckpoint = null
            checkpoints.trySend(Checkpoint(next, receipt)).getOrThrow()
        }
        if (serviceTransport) awaitPlaybackCheckpoint(receipt) else receipt.await()
    }
    /** Keep the structural receipt while servicing only the contiguous eligible transport prefix. */
    private suspend fun awaitPlaybackCheckpoint(receipt: Deferred<Unit>) {
        val attached = player
        val queueId = state.value.library.activeId
        val expectedGeneration = generation
        fun eligible(): Boolean = attached != null && player === attached && generation == expectedGeneration &&
            queueId != null && loadedQueueId == queueId && state.value.library.activeId == queueId &&
            attached.currentMediaItem?.listeningQueue == queueId && attached.playerError == null &&
            !holdsForEdit(state.value.library.active) && readers.recovery() == RecoveryAdmission.Clear
        while (!receipt.isCompleted && eligible()) {
            // Prefer the receipt when both clauses are ready. No transport crosses a structural head.
            val command = select<QueuedCommand?> {
                receipt.onAwait { null }
                commands.onReceiveCatching { it.getOrNull() }
            } ?: break
            if (!command.transportDuringSave) {
                commandAfterActivation = command
                break
            }
            yield() // Finish native event batches before touching the timeline again.
            if (!eligible()) {
                commandAfterActivation = command
                break
            }
            val revision = commandRevision
            deferCursorPersistence = true
            try { command.action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportProblem("Couldn’t save this queue change. Try again.") }
            finally {
                deferCursorPersistence = false
                commandRevision = revision
                // This snapshot is queued behind the initial receipt, never substituted for it.
                if (currentCoroutineContext().isActive && !checkpointCursor(state.value.library)) {
                    reportProblem("Couldn’t save this queue change. Try again.")
                }
            }
        }
        receipt.await()
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
        var activated = false
        try { start(); activated = true } finally { persist(state.value.library, serviceTransport = activated) }
    }
    private suspend fun syncTimeline(seek: Boolean = false, refreshLocations: Boolean = true,
        orderTiming: QueueOrderTiming? = null): Boolean {
        val attached = player ?: return false
        val queue = state.value.library.active ?: return false
        if (holdForEdit(attached, queue) || holdForRecovery(attached, queue) || queue.entries.isEmpty()) return false
        val recovery = readers.recovery()
        val window = if (recovery == RecoveryAdmission.Clear) requireNotNull(queue.journey).window()
            else recoveryWindow(queue) { recovery.blocks(documentIdentity(it)) }
        if (window.isEmpty()) return false
        val previous = resolved.takeIf { timeline.queueId == queue.id }.orEmpty()
        val next = withContext(Dispatchers.IO) {
            val wanted = window.mapTo(hashSetOf()) { it.entryId }
            val entries = queue.entries.filter { it.id in wanted }
            val read = if (refreshLocations) library.playbackSnapshot(entries.mapTo(linkedSetOf()) { it.track.id }) else null
            if (read != null) {
                orderTiming?.mark(QueueOrderTiming.Stage.FreshSnapshot)
                orderTiming?.recordCounts(libraryTracks = read.totalTracks)
            }
            val current = read?.tracks?.associateBy { it.id }.orEmpty()
            val tracks = library.playableTracks(entries.map { current[it.track.id] ?: it.track })
            var reused = 0
            entries.zip(tracks).associate { (entry, track) ->
                val old = previous[entry.id]
                entry.id to if (old != null && old.track == track && old.sourcePlaylistId == entry.sourcePlaylistId) {
                    reused++; old
                } else ResolvedOccurrence(track, entry.sourcePlaylistId, mediaItemFactory(entry.id, track, entry.sourcePlaylistId))
            }.also {
                orderTiming?.recordCounts(queueEntries = queue.entries.size, templatesReused = reused, templatesBuilt = it.size - reused)
                orderTiming?.mark(QueueOrderTiming.Stage.GrantsTemplates)
            }
        }
        if (player !== attached || state.value.library.active !== queue) return false
        if (!seek && loadedQueueId == queue.id && attached.currentMediaItem?.queueVisit != queue.journey?.current?.token) {
            mutable.update { it.copy(library = capturedLibrary(attached)) }
            if (state.value.library.active !== queue) return syncTimeline(seek, refreshLocations, orderTiming)
        }
        resolved = next
        timeline.resolve(queue.id, next.values.map { it.item })
        intents.preparing { pauseRequested ->
            if (pauseRequested) attached.pause()
            syncingTimeline = true
            try {
                val changed = timeline.sync(attached, queue, seek, loadedQueueId != queue.id || attached.mediaItemCount == 0, window)
                loadedQueueId = queue.id
                applyPlaybackModes(changed)
            } finally { syncingTimeline = false }
        }
        return true
    }
    private suspend fun activate(play: Boolean?, seek: Boolean = false, refreshLocations: Boolean = true,
        recordIntent: Boolean = true, orderTiming: QueueOrderTiming? = null) {
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
            // Saved queues keep their data; an empty native timeline needs no resolved templates.
            resolved = emptyMap()
            timeline.clear()
            return
        }
        if (!seek && loadedQueueId == queue.id) mutable.update { it.copy(library = capturedLibrary(attached)) }
        val latest = state.value.library.active ?: return
        mutable.update { it.copy(library = it.library.update(latest.copy(journey = journey(latest)))) }
        if (!syncTimeline(seek, refreshLocations, orderTiming)) return
        if (play != null || intents.pending() != null) applyNativeIntent(attached, revision, play ?: nativePlayIntent(attached))
        orderTiming?.mark(QueueOrderTiming.Stage.MainNativeApiReturned)
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

/** Atomic native position/visit sample used by the UI connection on Main. */
internal data class QueuePlaybackSample(val positionMs: Long, val playbackVisit: String?)

