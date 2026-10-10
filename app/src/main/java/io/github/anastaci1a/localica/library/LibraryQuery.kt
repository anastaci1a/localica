package io.github.anastaci1a.localica.library

import java.util.Locale

enum class TrackSort { Manual, Title, Artist, Album, AlbumArtist, Folder, Year, Duration, Composer, Genre, Modified, Count }

data class LibraryQuery(
    val text: String = "",
    val folderUri: String? = null,
    val sort: TrackSort = TrackSort.Title,
    val ascending: Boolean = true,
)

/** Shared, deterministic query semantics. Call off the main thread for large libraries. */
object LibraryQueries {
    fun tracks(snapshot: LibrarySnapshot, query: LibraryQuery): List<Track> =
        rows(snapshot.tracks.map { SongRow(it.id, it) }, query).map { it.track }

    fun rows(rows: List<SongRow>, query: LibraryQuery): List<SongRow> {
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
                TrackSort.Manual -> 0
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
                TrackSort.Composer -> compare(key(a.composer), key(b.composer))
                TrackSort.Genre -> compare(key(a.genre), key(b.genre))
                TrackSort.Modified -> compare(a.modifiedAt, b.modifiedAt)
                TrackSort.Count -> 0
            }
            if (query.sort == TrackSort.Manual) 0 else primary.takeIf { it != 0 } ?: a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
        val result = rows.asSequence().mapNotNull { row ->
            val track = row.track
            if (query.folderUri == null) row else {
                track.locations.firstOrNull { it.folderUri == query.folderUri }?.let {
                    row.copy(track = track.copy(uri = it.uri, relativePath = it.relativePath))
                }
            }
        }.filter { row ->
            val track = row.track
            if (terms.isEmpty()) true else {
                val paths = if (query.folderUri == null) track.locations.map { it.relativePath } + track.relativePath else listOf(track.relativePath)
                val searchable = (listOfNotNull(track.title, track.artist, track.album, track.albumArtist,
                    track.genre, track.comment, track.composer, track.lyricist, track.year?.toString(), track.fileName) + paths).joinToString("\n").lowercase(Locale.ROOT)
                terms.all { it in searchable }
            }
        }.sortedWith { a, b -> order.compare(a.track, b.track) }.toList()
        return if (query.sort == TrackSort.Manual && !query.ascending) result.reversed() else result
    }
}
