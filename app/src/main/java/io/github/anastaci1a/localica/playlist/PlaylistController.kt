package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Serial writes publish only committed state; failed restore never overwrites saved collections. */
class PlaylistController(private val dao: PlaylistDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private data class Edit(val change: (List<ManualPlaylist>) -> List<ManualPlaylist>, val complete: (Boolean) -> Unit)
    private val mutable = MutableStateFlow(PlaylistState())
    val state = mutable.asStateFlow()
    private val commands = Channel<Edit>(Channel.UNLIMITED)
    init {
        scope.launch {
            try { mutable.value = PlaylistState(withContext(Dispatchers.IO) { dao.read() }, loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = PlaylistState(loading = false, problem = "Couldn’t restore playlists. Reopen the app to retry.")
                commands.close()
                for (edit in commands) edit.complete(false)
                return@launch
            }
            for (edit in commands) try {
                val before = state.value.playlists
                val after = edit.change(before)
                withContext(Dispatchers.IO) { dao.save(before, after) }
                mutable.value = PlaylistState(after, loading = false)
                edit.complete(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(problem = "Couldn’t save this playlist change. Try again.") }
                edit.complete(false)
            }
        }
    }
    private fun submit(complete: (Boolean) -> Unit, change: (List<ManualPlaylist>) -> List<ManualPlaylist>) {
        if (commands.trySend(Edit(change, complete)).isFailure) complete(false)
    }
    fun create(name: String, tracks: List<Track> = emptyList(), complete: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { complete(false); return }
        submit(complete) { it + PlaylistEdits.append(ManualPlaylist(name = name.trim()), tracks) }
    }
    fun rename(id: String, name: String, complete: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { complete(false); return }
        edit(id, complete) { it.copy(name = name.trim()) }
    }
    fun append(id: String, tracks: List<Track>, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.append(it, tracks) }
    fun removeEntries(id: String, entries: Set<String>, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.remove(it, entries) }
    fun move(id: String, entry: String, offset: Int, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.move(it, entry, offset) }
    fun delete(id: String, complete: (Boolean) -> Unit = {}) { submit(complete) { playlists -> playlists.filterNot { it.id == id } } }
    fun refreshTrack(track: Track, complete: (Boolean) -> Unit) = submit(complete) { playlists ->
        playlists.map { list -> list.copy(entries = list.entries.map { entry ->
            if (entry.track.id == track.id) entry.copy(track = track) else entry
        }) }
    }
    private fun edit(id: String, complete: (Boolean) -> Unit, change: (ManualPlaylist) -> ManualPlaylist) {
        submit(complete) { playlists ->
            check(playlists.any { it.id == id })
            playlists.map { if (it.id == id) change(it) else it }
        }
    }
}
