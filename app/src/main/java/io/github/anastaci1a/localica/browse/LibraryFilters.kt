package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.navigation.MediaKind

enum class LibraryFilter(val kind: MediaKind?, val label: String) {
    All(null, "All"), Songs(MediaKind.Song, "Songs"), Albums(MediaKind.Album, "Albums"),
    Artists(MediaKind.Artist, "Artists"), AlbumArtists(MediaKind.AlbumArtist, "Album Artists"),
    Folders(MediaKind.Folder, "Folders"), Playlists(MediaKind.Playlist, "Playlists")
}
data class LibraryFilters(val visible: List<LibraryFilter> = LibraryFilter.entries,
    val allCollections: Set<MediaKind> = setOf(MediaKind.Album, MediaKind.AlbumArtist, MediaKind.Playlist))

class LibraryFilterPreferences(context: Context) {
    private val store = context.getSharedPreferences("library-filters", Context.MODE_PRIVATE)
    fun read(): LibraryFilters {
        val defaults = LibraryFilters()
        val visible = store.getString("visible", null)?.split(',')?.mapNotNull { name ->
            LibraryFilter.entries.firstOrNull { it.name == name }
        }?.distinct()?.takeIf { it.isNotEmpty() } ?: defaults.visible
        val included = store.getStringSet("all", null)?.mapNotNull { name ->
            MediaKind.entries.firstOrNull { it.name == name && it != MediaKind.Song && it != MediaKind.Queue }
        }?.toSet() ?: defaults.allCollections
        return LibraryFilters(visible, included)
    }
    fun write(value: LibraryFilters) {
        require(value.visible.isNotEmpty() && value.visible.distinct() == value.visible)
        require(value.allCollections.none { it == MediaKind.Song || it == MediaKind.Queue })
        store.edit().putString("visible", value.visible.joinToString(",") { it.name })
            .putStringSet("all", value.allCollections.map { it.name }.toSet()).apply()
    }
}
