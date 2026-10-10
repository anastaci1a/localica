package io.github.anastaci1a.localica.library

import java.util.Locale

/** Typed scopes are shared by queries and listening preferences. */
enum class SongListView(val label: String) {
    Songs("Songs"), Albums("Albums"), Artists("Artists"), AlbumArtists("Album Artists"),
    Folders("Folders"), Playlists("Playlists"), Search("Search")
}
data class SongRow(val id: String, val track: Track, val sourcePlaylistId: String? = null)
data class LibraryGroup(val id: String, val title: String, val subtitle: String?, val tracks: List<Track>)

object LibraryViews {
    fun defaults(view: SongListView) = LibraryQuery(sort = when (view) {
        SongListView.Albums, SongListView.Artists, SongListView.AlbumArtists -> TrackSort.Album
        SongListView.Folders -> TrackSort.Folder
        SongListView.Playlists -> TrackSort.Manual
        else -> TrackSort.Title
    })
    fun groups(snapshot: LibrarySnapshot, view: SongListView, query: LibraryQuery): List<LibraryGroup> {
        if (view == SongListView.Songs || view == SongListView.Playlists || view == SongListView.Search) return emptyList()
        val tracks = LibraryQueries.tracks(snapshot, query)
        fun key(text: String?) = text?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        val buckets = linkedMapOf<List<String?>, MutableList<Track>>()
        val labels = mutableMapOf<List<String?>, Pair<String, String?>>()
        fun add(id: List<String?>, title: String, subtitle: String?, track: Track) {
            buckets.getOrPut(id) { mutableListOf() }.add(track)
            labels.putIfAbsent(id, title to subtitle)
        }
        for (track in tracks) when (view) {
            SongListView.Albums -> {
                val artist = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
                add(listOf(key(track.album), key(artist)), track.album?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown album",
                    artist?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Album Artist", track)
            }
            SongListView.Artists -> add(listOf(key(track.artist)), track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", null, track)
            SongListView.AlbumArtists -> {
                val artist = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
                add(listOf(key(artist)), artist?.takeIf { it.isNotBlank() } ?: "Unknown Album Artist", null, track)
            }
            SongListView.Folders -> track.locations.filter { query.folderUri == null || it.folderUri == query.folderUri }.forEach { place ->
                val parent = place.relativePath.substringBeforeLast('/', "")
                val root = snapshot.folders.firstOrNull { it.uri == place.folderUri }?.name ?: place.folderUri
                add(listOf(place.folderUri, parent), parent.ifEmpty { root }, if (parent.isEmpty()) place.folderUri else root,
                    track.copy(uri = place.uri, relativePath = place.relativePath))
            }
            else -> Unit
        }
        return buckets.map { (key, members) ->
            val label = labels.getValue(key)
            // Length prefixes distinguish missing tags, delimiters in names, and literal "Unknown" tags.
            LibraryGroup(key.joinToString("") { it?.let { value -> "${value.length}:$value" } ?: "-1:" },
                label.first, label.second, members.distinctBy { it.id })
        }.sortedWith(compareBy<LibraryGroup> { it.title.lowercase(Locale.ROOT) }.thenBy { it.subtitle }.thenBy { it.id })
    }
}
