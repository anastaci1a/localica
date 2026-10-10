package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Serial writes publish only committed state; failed restore never overwrites saved collections. */
class PlaylistController(private val dao: PlaylistDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val mutable = MutableStateFlow(PlaylistState())
    val state = mutable.asStateFlow()
    private val commands = Channel<(List<ManualPlaylist>) -> List<ManualPlaylist>>(Channel.UNLIMITED)
    init {
        scope.launch {
            try { mutable.value = PlaylistState(withContext(Dispatchers.IO) { dao.read() }, loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = PlaylistState(loading = false, problem = "Couldn’t restore playlists. Reopen the app to retry.")
                commands.close()
                return@launch
            }
            for (edit in commands) try {
                val before = state.value.playlists
                val after = edit(before)
                withContext(Dispatchers.IO) { dao.save(before, after) }
                mutable.value = PlaylistState(after, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(problem = "Couldn’t save this playlist change. Try again.") } }
        }
    }
    fun create(name: String, tracks: List<Track> = emptyList()) {
        if (name.isBlank()) return
        commands.trySend { it + PlaylistEdits.append(ManualPlaylist(name = name.trim()), tracks) }
    }
    fun rename(id: String, name: String) { if (name.isNotBlank()) edit(id) { it.copy(name = name.trim()) } }
    fun append(id: String, tracks: List<Track>) = edit(id) { PlaylistEdits.append(it, tracks) }
    fun removeEntries(id: String, entries: Set<String>) = edit(id) { PlaylistEdits.remove(it, entries) }
    fun move(id: String, entry: String, offset: Int) = edit(id) { PlaylistEdits.move(it, entry, offset) }
    fun delete(id: String) { commands.trySend { playlists -> playlists.filterNot { it.id == id } } }
    private fun edit(id: String, change: (ManualPlaylist) -> ManualPlaylist) {
        commands.trySend { playlists -> playlists.map { if (it.id == id) change(it) else it } }
    }
}
