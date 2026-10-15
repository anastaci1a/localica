package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.*
import java.util.UUID

data class ManualPlaylist(val id: String = UUID.randomUUID().toString(), val name: String,
    val createdAt: Long = System.currentTimeMillis(), val entries: List<SongRow> = emptyList(), val modifiedAt: Long = createdAt,
    val coverUri: String? = null, val order: SavedOrder = SavedOrder(entries.map { it.id }))
data class PlaylistState(val playlists: List<ManualPlaylist> = emptyList(), val loading: Boolean = true,
    val problem: String? = null)
object PlaylistEdits {
    fun nameAvailable(playlists: List<ManualPlaylist>, name: String, exceptId: String? = null): Boolean =
        name.isNotBlank() && playlists.none { it.id != exceptId && it.name.trim().equals(name.trim(), ignoreCase = true) }

    fun addToDestinations(playlists: List<ManualPlaylist>, ids: Set<String>, name: String, tracks: List<Track>): List<ManualPlaylist> {
        require(ids.isNotEmpty() || name.isNotBlank()) { "Choose a playlist or enter a new name." }
        require(ids.all { id -> playlists.any { it.id == id } }) { "A selected playlist is no longer available." }
        require(name.isBlank() || nameAvailable(playlists, name)) { "A playlist with this name already exists." }
        val edited = playlists.map { if (it.id in ids) append(it, tracks).copy(modifiedAt = System.currentTimeMillis()) else it }
        return if (name.isBlank()) edited else edited + append(ManualPlaylist(name = name.trim()), tracks)
    }
    fun append(playlist: ManualPlaylist, tracks: List<Track>): ManualPlaylist {
        val added = tracks.map { SongRow(UUID.randomUUID().toString(), it) }
        return playlist.copy(entries = playlist.entries + added,
            order = playlist.order.copy(manual = playlist.order.manual + added.map { it.id }))
    }
    fun remove(playlist: ManualPlaylist, ids: Set<String>): ManualPlaylist {
        val entries = playlist.entries.filterNot { it.id in ids }
        return playlist.copy(entries = entries, order = playlist.order.reconcile(entries.map { it.id }))
    }
    fun move(playlist: ManualPlaylist, id: String, offset: Int): ManualPlaylist {
        val from = playlist.entries.indexOfFirst { it.id == id }
        if (from < 0) return playlist
        return reordered(playlist, OccurrenceMoves.byOffset(playlist.entries, from, offset))
    }
    private fun reordered(playlist: ManualPlaylist, entries: List<SongRow>) = if (entries == playlist.entries) playlist
        else playlist.copy(entries = entries, order = SavedOrder(entries.map { it.id }))
    fun moveBefore(playlist: ManualPlaylist, ids: Set<String>, beforeId: String?) = reordered(playlist,
        OccurrenceMoves.before(playlist.entries, ids, beforeId) { it.id })
    fun randomize(playlist: ManualPlaylist): ManualPlaylist {
        val entries = playlist.entries.shuffled()
        return playlist.copy(entries = entries, order = SavedOrder(entries.map { it.id }))
    }
    fun sort(playlist: ManualPlaylist, method: TrackSort, ascending: Boolean): ManualPlaylist {
        val byId = playlist.entries.associateBy { it.id }
        return playlist.copy(entries = playlist.order.apply(playlist.entries, method, ascending).map { byId.getValue(it) },
            order = playlist.order.copy(sort = method, ascending = ascending))
    }
    /** A rescan updates available metadata without losing unavailable references or occurrence IDs. */
    fun rows(playlist: ManualPlaylist, snapshot: LibrarySnapshot, query: LibraryQuery,
        tracks: Map<String, Track> = snapshot.tracks.associateBy { it.id }): List<SongRow> {
        return LibraryQueries.rows(playlist.entries.map { it.copy(track = tracks[it.track.id] ?: it.track.copy(playCount = snapshot.trackPlays[it.track.id] ?: 0), sourcePlaylistId = playlist.id) }, query)
    }
}
