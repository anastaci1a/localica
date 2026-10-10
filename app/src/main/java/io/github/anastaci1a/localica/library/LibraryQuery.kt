package io.github.anastaci1a.localica.library

import java.util.Locale

enum class TrackSort { Title, Artist, Album, AlbumArtist, Folder, Year, Duration }

data class LibraryQuery(
    val text: String = "",
    val folderUri: String? = null,
    val sort: TrackSort = TrackSort.Title,
    val ascending: Boolean = true,
)

/** Shared, deterministic query semantics. Call off the main thread for large libraries. */
object LibraryQueries {
    fun tracks(snapshot: LibrarySnapshot, query: LibraryQuery): List<Track> {
        val terms = query.text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val direction = if (query.ascending) 1 else -1
        fun <T : Comparable<T>> compare(a: T?, b: T?): Int = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b) * direction
        }
        fun key(value: String?) = value?.lowercase(Locale.ROOT)
        val order = Comparator<Track> { a, b ->
            val primary = when (query.sort) {
                TrackSort.Title -> compare(key(a.title), key(b.title))
                TrackSort.Artist -> compare(key(a.artist), key(b.artist))
                TrackSort.AlbumArtist -> compare(key(a.albumArtist ?: a.artist), key(b.albumArtist ?: b.artist))
                TrackSort.Album -> compare(key(a.album), key(b.album)).takeIf { it != 0 }
                    ?: compare(key(a.albumArtist ?: a.artist), key(b.albumArtist ?: b.artist)).takeIf { it != 0 }
                    ?: compare(a.discNumber, b.discNumber).takeIf { it != 0 }
                    ?: compare(a.trackNumber, b.trackNumber)
                TrackSort.Folder -> compare(key(a.relativePath), key(b.relativePath))
                TrackSort.Year -> compare(a.year, b.year)
                TrackSort.Duration -> compare(a.durationMs, b.durationMs)
            }
            primary.takeIf { it != 0 } ?: a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
        return snapshot.tracks.asSequence().mapNotNull { track ->
            if (query.folderUri == null) track else {
                track.locations.firstOrNull { it.folderUri == query.folderUri }?.let {
                    track.copy(uri = it.uri, relativePath = it.relativePath)
                }
            }
        }.filter { track ->
            if (terms.isEmpty()) true else {
                val paths = if (query.folderUri == null) track.locations.map { it.relativePath } else listOf(track.relativePath)
                val searchable = (listOfNotNull(track.title, track.artist, track.album, track.albumArtist,
                    track.genre, track.comment, track.year?.toString(), track.fileName) + paths).joinToString("\n").lowercase(Locale.ROOT)
                terms.all { it in searchable }
            }
        }.sortedWith(order).toList()
    }
}
