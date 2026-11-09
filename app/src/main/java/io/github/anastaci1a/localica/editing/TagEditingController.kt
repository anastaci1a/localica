package io.github.anastaci1a.localica.editing

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.PlaylistController
import io.github.anastaci1a.localica.queue.QueueController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume

data class TagEditorState(val tracks: List<Track> = emptyList(), val busy: Boolean = false,
    val results: List<EditResult> = emptyList(), val recovery: String? = null, val error: String? = null,
    val background: Boolean = false, val artworkOnly: Boolean = false, val jobsVisible: Boolean = false, val jobs: List<TagJob> = emptyList(),
    val recoveryProblem: String? = null, val sessionId: String = "",
    val progressVisible: Boolean = false, val phase: String = "", val batchId: String? = null,
    val undo: Boolean = true, val saved: Int = 0, val restored: Int = 0, val touched: Int = 0,
    val interruptedBatch: Boolean = false, val draft: String? = null, val spaceProblem: String? = null,
    val cancelPending: Boolean = false)

class TagEditingController(private val context: Context, private val library: LibraryRepository,
    private val queues: QueueController, private val playlists: PlaylistController,
    private val writer: RecoverableTagWriter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documents = AndroidDocuments(context)
    private val history = TagJobHistory(context)
    private val refreshJournal = MetadataRefreshJournal(File(context.filesDir, "pending-metadata-refresh.json"))
    private val mutable = MutableStateFlow(TagEditorState(jobs = history.read()))
    private var pendingPatch: TagPatch? = null
    private var pendingDraft: String? = null
    private var pendingUndo = true
    private val worker = Mutex()
    private val transaction = TagBatchTransaction(writer,
        editing = { track, action -> library.withExclusiveFiles { queues.withFileEdit(track.id) { checkHeadroom(track); action() } } },
        recovering = { track, action -> library.withExclusiveFiles { queues.withFileRecovery(track.id, action) } },
        refresh = { track -> withContext(Dispatchers.IO) { refreshJournal.include(track) } },
        changed = { batch -> publish(batch) })
    private var activeJob: String? = null
    val state = mutable.asStateFlow()
    private var stopped = false
    private var completion: ((Boolean) -> Unit)? = null
    private val initialized = scope.async {
        checkRecovery()
        try {
            val batch = withContext(Dispatchers.IO) { writer.batches.read() }
            if (batch != null) {
                if (batch.terminal) withContext(Dispatchers.IO) { writer.batches.cleanup(batch.id) }
                else {
                    val retained = withContext(Dispatchers.IO) {
                        if (batch.phase == TagBatchPhase.Saving) writer.batches.changePhase(batch.id, TagBatchPhase.Interrupted) else batch
                    }
                    mutable.update { it.copy(tracks = retained.tracks, sessionId = retained.id, draft = retained.draft,
                        progressVisible = true, interruptedBatch = true,
                        cancelPending = retained.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring)) }
                    publish(retained)
                }
            }
        } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
        try { library.withExclusiveFiles { flushMetadata() } }
        catch (failure: Exception) { mutable.update { it.copy(error = "Saved tags need refreshing: ${failure.message}") } }
    }
    fun open(tracks: List<Track>, complete: (Boolean) -> Unit = {}) {
        if (state.value.busy || state.value.batchId != null) { showJobs(); return }
        completion?.invoke(false); completion = complete
        mutable.update { it.copy(sessionId = java.util.UUID.randomUUID().toString(), tracks = tracks.distinctBy { track -> documentIdentity(track.id) },
            results = emptyList(), error = null, background = false, artworkOnly = false, draft = null, progressVisible = false) }
    }
    fun openArtwork(track: Track) {
        if (state.value.busy) { showJobs(); return }
        open(listOf(track))
        mutable.update { it.copy(artworkOnly = true) }
    }
    fun close() {
        if (state.value.busy || state.value.batchId != null) return
        completion?.invoke(state.value.results.size == state.value.tracks.size && state.value.results.all { it.saved })
        completion = null; mutable.update { it.copy(tracks = emptyList(), results = emptyList(), error = null) }
    }
    fun continueInBackground() { scope.launch {
        if (state.value.cancelPending || state.value.interruptedBatch) return@launch
        try {
            val id = state.value.batchId ?: return@launch
            withContext(Dispatchers.IO) { writer.batches.background(id) }
            mutable.update { it.copy(background = true, progressVisible = false) }
            // The worker may have sampled background=false immediately before this
            // acceptance. Recheck the committed outcome after it releases ownership.
            worker.withLock {
                val finished = withContext(Dispatchers.IO) { writer.batches.read() } ?: return@withLock
                if (state.value.cancelPending || state.value.interruptedBatch ||
                    finished.phase != TagBatchPhase.AwaitingDecision || finished.saved != finished.tracks.size) return@withLock
                transaction.commit(id)
                completion?.invoke(true); completion = null
                mutable.update { it.copy(batchId = null, tracks = emptyList(), results = emptyList(), progressVisible = false, draft = null) }
            }
        } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
    } }
    fun showJobs() { mutable.update { it.copy(jobsVisible = it.batchId == null, background = if (it.batchId != null) false else it.background,
        progressVisible = it.batchId != null || it.progressVisible) } }
    fun hideJobs() { mutable.update { it.copy(jobsVisible = false) } }
    fun serviceFailed(message: String) {
        pendingPatch = null
        mutable.update { it.copy(busy = false, error = message) }
        scope.launch { recordJob("Stopped: $message") }
    }
    fun stopAfterCurrent() { stopped = true }
    fun cancelBatch() {
        stopped = true // Close the next-write admission synchronously on Main.
        mutable.update { it.copy(cancelPending = true) }
        scope.launch {
            try {
                val id = state.value.batchId
                if (id != null) transaction.requestCancel(id)
                if (!state.value.busy && id != null) startWorker()
            } catch (failure: Exception) { mutable.update { it.copy(error =
                "Cancellation was not recorded or could not be verified. No more files will be written in this session. Retry Restore. After restarting, choose Restore for the interrupted work. ${failure.message.orEmpty()}") } }
        }
    }
    fun interrupted() {
        stopped = true
        if (state.value.busy) mutable.update { it.copy(error = "Android stopped background editing. Check the results and tag recovery before retrying.") }
    }
    fun save(patch: TagPatch) = saveDraft(patch, null)
    fun saveDraft(patch: TagPatch, draft: String?) {
        if (state.value.busy || state.value.batchId != null) return
        try { patch.validate() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; return }
        stopped = false
        pendingPatch = patch
        pendingDraft = draft
        pendingUndo = true
        activeJob = java.util.UUID.randomUUID().toString()
        mutable.update { it.copy(busy = true, results = emptyList(), error = null, progressVisible = true,
            phase = "Preparing", saved = 0, restored = 0, touched = 0, undo = true, spaceProblem = null,
            jobs = (listOf(TagJob(activeJob!!, it.tracks.size)) + it.jobs).take(8)) }
        startWorker()
    }
    private fun startWorker() {
        mutable.update { it.copy(busy = true, error = null) }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context,
                android.content.Intent(context, TagEditService::class.java))
        } catch (failure: Exception) { serviceFailed("Android couldn’t start background editing. Keep the app open and retry.") }
    }
    fun saveWithoutUndo() {
        if (state.value.busy || state.value.spaceProblem == null || pendingPatch == null) return
        pendingUndo = false; stopped = false
        mutable.update { it.copy(undo = false, spaceProblem = null) }
        startWorker()
    }
    fun dismissPreparation() {
        if (state.value.busy || state.value.batchId != null) return
        pendingPatch = null; pendingDraft = null
        mutable.update { it.copy(progressVisible = false, spaceProblem = null, error = null, cancelPending = false) }
    }
    fun resumeBatch() {
        if (state.value.busy || state.value.batchId == null || state.value.cancelPending) return
        stopped = false
        mutable.update { it.copy(interruptedBatch = false) }
        startWorker()
    }
    fun finishBatch() {
        if (state.value.busy || state.value.interruptedBatch || state.value.cancelPending) return
        scope.launch { worker.withLock {
            try {
                val id = state.value.batchId ?: return@withLock
                transaction.commit(id)
                val success = state.value.saved == state.value.tracks.size
                completion?.invoke(success); completion = null
                mutable.update { it.copy(batchId = null, progressVisible = false, tracks = emptyList(), results = emptyList(), draft = null) }
            } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
        } }
    }
    internal suspend fun retainedPatch(): TagPatch? = withContext(Dispatchers.IO) { writer.batches.read()?.patch }

    private suspend fun publish(batch: TagBatch) = withContext(Dispatchers.Main.immediate) {
        if (batch.terminal) {
            mutable.update { it.copy(phase = batch.phase.name) }
            return@withContext
        }
        mutable.update { current -> current.copy(batchId = batch.id, phase = batch.phase.name, undo = batch.undo, draft = batch.draft,
            cancelPending = current.cancelPending || batch.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring),
            saved = batch.saved, restored = batch.restored, touched = batch.touched,
            results = batch.entries.values.sortedBy { it.index }.mapNotNull { entry ->
                when (entry.phase) {
                    TagBatchEntryPhase.Saved -> EditResult(batch.tracks[entry.index], true, "Saved and verified")
                    TagBatchEntryPhase.Failed -> EditResult(batch.tracks[entry.index], false, entry.message ?: "Couldn’t save this file.")
                    else -> null
                }
            }) }
    }
    private suspend fun checkHeadroom(track: Track) {
        val source = writer.source(track)
        val batch = withContext(Dispatchers.IO) { requireNotNull(writer.batches.read()) }
        val artwork = (batch.patch?.artwork as? ArtworkChange.Replace)?.image?.bytes?.size?.toLong() ?: 0L
        val needed = TagSaveSpace.required(source.size, source.size, artwork, batch.undo && batch.entries.values.none { it.uri == source.uri })
        check(withContext(Dispatchers.IO) { context.filesDir.usableSpace } >= needed) {
            "Free space changed while saving. Undo copies are retained; free space and retry."
        }
    }
    private suspend fun preflight(tracks: List<Track>, patch: TagPatch, undo: Boolean): Boolean {
        var total = 0L; var largest = 0L
        for (track in tracks.distinctBy { documentIdentity(it.id) }) {
            if (stopped) return false
            val source = library.withExclusiveFiles { queues.withFileEdit(track.id) { writer.source(track) } }
            total = Math.addExact(total, source.size); largest = maxOf(largest, source.size)
        }
        val artwork = (patch.artwork as? ArtworkChange.Replace)?.image?.bytes?.size?.toLong() ?: 0L
        val needed = TagSaveSpace.required(total, largest, artwork, undo)
        val free = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
        if (free >= needed) return true
        val message = "Not enough free space to keep " + (if (undo) "undo copies" else "working copies") + ": needs $needed bytes, $free bytes free"
        mutable.update { it.copy(spaceProblem = message, error = if (undo) null else message) }
        return false
    }
    suspend fun runPending() = worker.withLock {
        initialized.await()
        if (!state.value.busy) return@withLock
        try {
            var batch = withContext(Dispatchers.IO) { writer.batches.read() }
            if (batch == null) {
                val patch = pendingPatch ?: return@withLock
                if (!preflight(state.value.tracks, patch, pendingUndo) || stopped) return@withLock
                batch = withContext(Dispatchers.IO) { writer.batches.begin(state.value.tracks, patch, pendingDraft, pendingUndo) }
                pendingPatch = null
                publish(batch)
            } else {
                check(!batch.terminal) { "Finishing retained work; retry after cleanup." }
                transaction.reconcile(batch.id)
                batch = withContext(Dispatchers.IO) { requireNotNull(writer.batches.read()) }
            }
            if (batch.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring) || ((stopped || state.value.cancelPending) && batch.undo)) {
                withContext(NonCancellable) { transaction.restore(batch.id) }
                mutable.update { it.copy(batchId = null, saved = 0, results = emptyList(), progressVisible = false,
                    interruptedBatch = false, phase = "", background = false, error = null, cancelPending = false) }
                recordJob("Cancelled; originals restored")
                return@withLock
            }
            batch = withContext(Dispatchers.IO) { writer.batches.changePhase(batch.id, TagBatchPhase.Saving) }
            publish(batch)
            recordJob("Editing")
            for (index in batch.tracks.indices) {
                if (stopped || state.value.cancelPending) break
                // A cancelled service still waits for the acquired file and its outcome record.
                withContext(NonCancellable) { transaction.writeOne(batch.id, index) }
                recordJob("Editing")
                currentCoroutineContext().ensureActive()
            }
            val latest = withContext(Dispatchers.IO) { requireNotNull(writer.batches.read()) }
            if (latest.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring) || ((stopped || state.value.cancelPending) && latest.undo)) {
                withContext(NonCancellable) { transaction.restore(batch.id) }
                mutable.update { it.copy(batchId = null, saved = 0, results = emptyList(), progressVisible = false,
                    interruptedBatch = false, phase = "", background = false, error = null, cancelPending = false) }
                recordJob("Cancelled; originals restored")
            } else {
                publish(withContext(Dispatchers.IO) { writer.batches.changePhase(batch.id, TagBatchPhase.AwaitingDecision) })
                recordJob(if (state.value.saved == batch.tracks.size) "Complete; awaiting Done" else "Finished with errors or stopped")
                if (latest.background && state.value.saved == batch.tracks.size) {
                    transaction.commit(batch.id)
                    completion?.invoke(true); completion = null
                    mutable.update { it.copy(batchId = null, tracks = emptyList(), results = emptyList(), progressVisible = false, draft = null) }
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try {
                    val batch = withContext(Dispatchers.IO) { writer.batches.read() }
                    if (batch != null && !batch.terminal) {
                        val retained = withContext(Dispatchers.IO) {
                            if (batch.phase == TagBatchPhase.Saving) writer.batches.changePhase(batch.id, TagBatchPhase.Interrupted) else batch
                        }
                        publish(retained)
                    }
                } catch (_: Exception) { /* The writer's recovery probe below owns admission. */ }
                mutable.update { it.copy(error = failure.message ?: "Editing interrupted.") }
                recordJob("Stopped: "+state.value.error)
            }
        } finally { withContext(NonCancellable) {
            checkRecovery()
            try { library.withExclusiveFiles { flushMetadata() } }
            catch (failure: Exception) { mutable.update { it.copy(error = "Saved tags need refreshing: "+failure.message) } }
            mutable.update { it.copy(busy = false, progressVisible = if (it.batchId == null && stopped) false else it.progressVisible,
                cancelPending = if (it.batchId == null && stopped) false else it.cancelPending) }
        } }
    }
    private suspend fun recordJob(status: String) {
        val id = activeJob ?: return
        mutable.update { current -> current.copy(jobs = current.jobs.map { job ->
            if (job.id != id) job else job.copy(status = status, outcomes = current.results.map { "${it.track.fileName}: ${it.message}" })
        }) }
        try { withContext(Dispatchers.IO) { history.write(state.value.jobs) } }
        catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; stopped = true }
    }
    fun recover() {
        if (state.value.busy) return
        val request = queues.recoveryRequested()
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { library.withExclusiveFiles {
                val pending = withContext(Dispatchers.IO) { writer.pending() } ?: return@withExclusiveFiles
                queues.withFileRecovery(pending.track.id, request) {
                    withContext(Dispatchers.IO) { refreshJournal.include(pending.track.copy(uri = pending.uri)) }
                    val restored = writer.recover()
                    withContext(Dispatchers.IO) { refreshJournal.include(restored) }
                    flushMetadata()
                }
            } } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
            finally {
                queues.finishRecovery(request)
                mutable.update { it.copy(busy = false) }; checkRecovery()
            }
        }
    }
    private suspend fun flushMetadata() = withContext(Dispatchers.IO) {
        val pending = refreshJournal.read()
        val recovering = writer.pending()?.track?.id
        val ready = pending.filterNot { it.id == recovering }
        if (ready.isEmpty()) return@withContext
        val refreshed = library.refreshMetadataBatch(ready.map { it to documents.metadata(it.uri, it.fileName) })
        check(await<Boolean> { playlists.refreshTracks(refreshed, it) }) { "Playlist refresh failed; pending refresh retained." }
        check(await<Boolean> { queues.refreshTracks(refreshed, it) }) { "Queue refresh failed; pending refresh retained." }
        ArtworkCache.invalidate()
        refreshJournal.write(pending.filter { it.id == recovering })
    }
    private suspend fun checkRecovery() {
        val (name, problem) = withContext(Dispatchers.IO) {
            try {
                val pending = writer.pending()
                pending?.track?.fileName to if (pending != null && !pending.original.isFile)
                    "The recovery copy is missing. Keep the recovery record and restore this song from another backup." else null
            } catch (failure: Exception) {
                "Unreadable recovery record" to
                    "Recovery could not be read. Retry when storage is available; keep the retained files. ${failure.message.orEmpty()}"
            }
        }
        mutable.update { it.copy(recovery = name, recoveryProblem = problem) }
    }
    private suspend fun <T> await(action: ((T) -> Unit) -> Unit): T = withTimeout(15000) {
        suspendCancellableCoroutine { continuation -> action { if (continuation.isActive) continuation.resume(it) } }
    }
}
