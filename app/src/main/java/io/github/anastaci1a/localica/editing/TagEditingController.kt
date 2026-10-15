package io.github.anastaci1a.localica.editing

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.PlaylistController
import io.github.anastaci1a.localica.queue.QueueController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.resume

data class TagEditorState(val tracks: List<Track> = emptyList(), val busy: Boolean = false,
    val results: List<EditResult> = emptyList(), val recovery: String? = null, val error: String? = null,
    val background: Boolean = false, val artworkOnly: Boolean = false, val jobsVisible: Boolean = false, val jobs: List<TagJob> = emptyList(),
    val recoveryProblem: String? = null)

class TagEditingController(private val context: Context, private val library: LibraryRepository,
    private val queues: QueueController, private val playlists: PlaylistController,
    private val writer: RecoverableTagWriter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documents = AndroidDocuments(context)
    private val history = TagJobHistory(context)
    private val refreshJournal = MetadataRefreshJournal(File(context.filesDir, "pending-metadata-refresh.json"))
    private val mutable = MutableStateFlow(TagEditorState(jobs = history.read()))
    private var pendingPatch: TagPatch? = null
    private var activeJob: String? = null
    val state = mutable.asStateFlow()
    private var stopped = false
    private var completion: ((Boolean) -> Unit)? = null
    init { scope.launch {
        checkRecovery()
        try { library.withExclusiveFiles { flushMetadata() } }
        catch (failure: Exception) { mutable.update { it.copy(error = "Saved tags need refreshing: ${failure.message}") } }
    } }
    fun open(tracks: List<Track>, complete: (Boolean) -> Unit = {}) {
        if (state.value.busy) { showJobs(); return }
        completion?.invoke(false); completion = complete
        mutable.update { it.copy(tracks = tracks.distinctBy(Track::id), results = emptyList(), error = null, background = false, artworkOnly = false) }
    }
    fun openArtwork(track: Track) {
        if (state.value.busy) { showJobs(); return }
        open(listOf(track))
        mutable.update { it.copy(artworkOnly = true) }
    }
    fun close() {
        if (state.value.busy) return
        completion?.invoke(state.value.results.size == state.value.tracks.size && state.value.results.all { it.saved })
        completion = null; mutable.update { it.copy(tracks = emptyList(), results = emptyList(), error = null) }
    }
    fun continueInBackground() { mutable.update { it.copy(background = true) } }
    fun showJobs() { mutable.update { it.copy(jobsVisible = true) } }
    fun hideJobs() { mutable.update { it.copy(jobsVisible = false) } }
    fun serviceFailed(message: String) {
        pendingPatch = null
        mutable.update { it.copy(busy = false, error = message) }
        scope.launch { recordJob("Stopped: $message") }
    }
    fun stopAfterCurrent() { stopped = true }
    fun interrupted() {
        stopped = true
        if (state.value.busy) mutable.update { it.copy(error = "Android stopped background editing. Check the results and tag recovery before retrying.") }
    }
    fun save(patch: TagPatch) {
        if (state.value.busy) return
        try { patch.validate() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; return }
        stopped = false
        pendingPatch = patch
        activeJob = java.util.UUID.randomUUID().toString()
        mutable.update { it.copy(busy = true, results = emptyList(), error = null,
            jobs = (listOf(TagJob(activeJob!!, it.tracks.size)) + it.jobs).take(8)) }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context,
                android.content.Intent(context, TagEditService::class.java))
        } catch (failure: Exception) { serviceFailed("Android couldn’t start background editing. Keep the app open and retry.") }
    }
    suspend fun runPending() {
        val patch = pendingPatch ?: return
        pendingPatch = null
        val tracks = state.value.tracks
        recordJob("Editing")
            try {
                for (track in tracks) {
                    if (stopped || state.value.recovery != null) break
                    val result = library.withExclusiveFiles {
                        try {
                            check(writer.canWrite(track)) { "Grant write access to this song’s folder first." }
                            queues.withFileEdit(track.id) {
                                withContext(Dispatchers.IO) { refreshJournal.include(track) }
                                val written = writer.edit(track, patch)
                                withContext(Dispatchers.IO) { refreshJournal.include(written) }
                                writer.complete()
                                EditResult(track, true, "Saved and verified")
                            }
                        } catch (failure: Exception) {
                            EditResult(track, false, failure.message ?: "Couldn’t save this file.")
                        }
                    }
                    mutable.update { it.copy(results = it.results + result) }
                    recordJob("Editing")
                    checkRecovery()
                }
            } catch (failure: Exception) { mutable.update { it.copy(error = it.error ?: if (failure is CancellationException) "Editing interrupted." else failure.message ?: "Editing stopped.") } }
            finally { withContext(NonCancellable) {
                checkRecovery()
                try { library.withExclusiveFiles { flushMetadata() } }
                catch (failure: Exception) { mutable.update { it.copy(error = "Saved tags need refreshing: ${failure.message}") } }
                val current = state.value
                recordJob(when {
                    current.error != null -> "Stopped: ${current.error}"
                    current.results.size < tracks.size -> "Stopped after ${current.results.size} of ${tracks.size} files"
                    current.results.any { !it.saved } -> "Finished with errors"
                    else -> "Complete"
                })
                mutable.update { it.copy(busy = false) }
                if (current.background) {
                    completion?.invoke(current.results.size == tracks.size && current.results.all { it.saved })
                    completion = null
                }
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
