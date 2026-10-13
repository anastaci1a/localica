package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Serial writes publish only committed state; failed restore never overwrites saved collections. */
class PlaylistController(private val dao: PlaylistDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val coverDirectory: java.io.File? = null) {
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
                // Only unreferenced copies owned by this app are disposable, and only
                // after the database has committed the replacement/deletion.
                coverDirectory?.let { directory -> withContext(Dispatchers.IO) {
                    val retained = after.mapNotNull { it.coverUri }.toSet()
                    before.mapNotNull { it.coverUri }.distinct().filterNot { it in retained }.forEach { uri ->
                        runCatching {
                            val parsed = android.net.Uri.parse(uri)
                            if (parsed.scheme == "file") parsed.path?.let { path ->
                                val file = java.io.File(path).canonicalFile
                                if (file.parentFile == directory.canonicalFile) file.delete()
                            }
                        }
                    }
                } }
                edit.complete(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) {
                mutable.update { it.copy(problem = invalid.message ?: "This playlist change is invalid.") }
                edit.complete(false)
            }
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
        addToDestinations(emptySet(), name, tracks, complete)
    }
    fun rename(id: String, name: String, complete: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { complete(false); return }
        submit(complete) { playlists ->
            require(PlaylistEdits.nameAvailable(playlists, name, id)) { "A playlist with this name already exists." }
            require(playlists.any { it.id == id }) { "This playlist is no longer available." }
            playlists.map { if (it.id == id && it.name != name.trim())
                it.copy(name = name.trim(), modifiedAt = System.currentTimeMillis()) else it }
        }
    }
    fun addToDestinations(ids: Set<String>, name: String, tracks: List<Track>, complete: (Boolean) -> Unit = {}) =
        submit(complete) { PlaylistEdits.addToDestinations(it, ids, name, tracks) }
    fun append(id: String, tracks: List<Track>, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.append(it, tracks) }
    fun cover(id: String, uri: String, complete: (Boolean) -> Unit = {}) = edit(id, complete) { it.copy(coverUri = uri) }
    fun removeEntries(id: String, entries: Set<String>, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.remove(it, entries) }
    fun move(id: String, entry: String, offset: Int, complete: (Boolean) -> Unit = {}) = edit(id, complete) { PlaylistEdits.move(it, entry, offset) }
    fun moveBefore(id: String, entries: Set<String>, beforeId: String?, complete: (Boolean) -> Unit = {}) =
        edit(id, complete) { PlaylistEdits.moveBefore(it, entries, beforeId) }
    fun delete(id: String, complete: (Boolean) -> Unit = {}) { submit(complete) { playlists -> playlists.filterNot { it.id == id } } }
    fun refreshTrack(track: Track, complete: (Boolean) -> Unit) = submit(complete) { playlists ->
        playlists.map { list -> list.copy(entries = list.entries.map { entry ->
            if (entry.track.id == track.id) entry.copy(track = track) else entry
        }) }
    }
    private fun edit(id: String, complete: (Boolean) -> Unit, change: (ManualPlaylist) -> ManualPlaylist) {
        submit(complete) { playlists ->
            check(playlists.any { it.id == id })
            playlists.map { old -> if (old.id == id) {
                val next = change(old)
                if (next == old) old else next.copy(modifiedAt = System.currentTimeMillis())
            } else old }
        }
    }
}
