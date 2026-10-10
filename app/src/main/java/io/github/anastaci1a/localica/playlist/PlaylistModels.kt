package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.*
import java.util.UUID

data class ManualPlaylist(val id: String = UUID.randomUUID().toString(), val name: String,
    val createdAt: Long = System.currentTimeMillis(), val entries: List<SongRow> = emptyList())
data class PlaylistState(val playlists: List<ManualPlaylist> = emptyList(), val loading: Boolean = true,
    val problem: String? = null)
object PlaylistEdits {
    fun append(playlist: ManualPlaylist, tracks: List<Track>) = playlist.copy(
        entries = playlist.entries + tracks.map { SongRow(UUID.randomUUID().toString(), it) })
    fun remove(playlist: ManualPlaylist, ids: Set<String>) = playlist.copy(entries = playlist.entries.filterNot { it.id in ids })
    fun move(playlist: ManualPlaylist, id: String, offset: Int): ManualPlaylist {
        val from = playlist.entries.indexOfFirst { it.id == id }
        if (from < 0) return playlist
        val to = (from + offset).coerceIn(0, playlist.entries.lastIndex)
        return playlist.copy(entries = playlist.entries.toMutableList().apply { add(to, removeAt(from)) })
    }
    /** A rescan updates available metadata without losing unavailable references or occurrence IDs. */
    fun rows(playlist: ManualPlaylist, snapshot: LibrarySnapshot, query: LibraryQuery): List<SongRow> {
        val tracks = snapshot.tracks.associateBy { it.id }
        return LibraryQueries.rows(playlist.entries.map { it.copy(track = tracks[it.track.id] ?: it.track) }, query)
    }
}
