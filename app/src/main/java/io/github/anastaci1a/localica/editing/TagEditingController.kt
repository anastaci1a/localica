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
    val results: List<EditResult> = emptyList(), val recovery: String? = null, val error: String? = null)

class TagEditingController(context: Context, private val library: LibraryRepository,
    private val queues: QueueController, private val playlists: PlaylistController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documents = AndroidDocuments(context)
    private val writer = RecoverableTagWriter(File(context.filesDir, "tag-recovery"), AndroidEditableDocuments(context))
    private val mutable = MutableStateFlow(TagEditorState())
    val state = mutable.asStateFlow()
    private var stopped = false
    private var completion: ((Boolean) -> Unit)? = null
    init { scope.launch { checkRecovery() } }
    fun open(tracks: List<Track>, complete: (Boolean) -> Unit = {}) {
        if (state.value.busy) return
        completion?.invoke(false); completion = complete
        mutable.update { it.copy(tracks = tracks.distinctBy(Track::id), results = emptyList(), error = null) }
    }
    fun close() {
        if (state.value.busy) return
        completion?.invoke(state.value.results.size == state.value.tracks.size && state.value.results.all { it.saved })
        completion = null; mutable.update { it.copy(tracks = emptyList(), results = emptyList(), error = null) }
    }
    fun stopAfterCurrent() { stopped = true }
    fun save(patch: TagPatch) {
        if (state.value.busy) return
        try { patch.validate() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; return }
        stopped = false
        val tracks = state.value.tracks
        mutable.update { it.copy(busy = true, results = emptyList(), error = null) }
        scope.launch {
            try {
                for (track in tracks) {
                    if (stopped || state.value.recovery != null) break
                    val result = library.withExclusiveFiles {
                        var token: QueueController.EditPlayback? = null
                        try {
                            check(writer.canWrite(track)) { "Grant write access to this song’s folder first." }
                            token = await { queues.beginFileEdit(track.id, it) }
                            val written = writer.edit(track, patch)
                            refresh(written)
                            writer.complete()
                            EditResult(track, true, "Saved and verified")
                        } catch (failure: Exception) {
                            EditResult(track, false, failure.message ?: "Couldn’t save this file.")
                        } finally { await<Boolean> { queues.endFileEdit(token, it) } }
                    }
                    mutable.update { it.copy(results = it.results + result) }
                    checkRecovery()
                }
            } catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Editing stopped.") } }
            finally { mutable.update { it.copy(busy = false) }; checkRecovery() }
        }
    }
    fun recover() {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { library.withExclusiveFiles {
                val pending = withContext(Dispatchers.IO) { writer.pending() } ?: return@withExclusiveFiles
                val token = await<QueueController.EditPlayback?> { queues.beginFileEdit(pending.track.id, it) }
                try { refresh(writer.recover()) } finally { await<Boolean> { queues.endFileEdit(token, it) } }
            } } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
            finally { mutable.update { it.copy(busy = false) }; checkRecovery() }
        }
    }
    private suspend fun refresh(track: Track) {
        val refreshed = library.refreshMetadata(track, documents.metadata(track.uri, track.fileName))
        check(await<Boolean> { playlists.refreshTrack(refreshed, it) }) { "File saved; playlist refresh failed. Recovery copy retained." }
        check(await<Boolean> { queues.refreshTrack(refreshed, it) }) { "File saved; queue refresh failed. Recovery copy retained." }
        ArtworkCache.invalidate()
    }
    private suspend fun checkRecovery() {
        val name = withContext(Dispatchers.IO) { runCatching { writer.pending()?.track?.fileName }.getOrElse { "Unreadable recovery record" } }
        mutable.update { it.copy(recovery = name) }
    }
    private suspend fun <T> await(action: ((T) -> Unit) -> Unit): T = withTimeout(15000) {
        suspendCancellableCoroutine { continuation -> action { if (continuation.isActive) continuation.resume(it) } }
    }
}
