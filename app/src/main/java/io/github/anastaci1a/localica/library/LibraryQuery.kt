package io.github.anastaci1a.localica.library

import java.util.Locale

enum class TrackSort { Manual, Title, Artist, Album, AlbumArtist, Folder, Year, Duration, Composer, Genre, Modified, Added, MostPlayed, Count }

data class LibraryQuery(
    val text: String = "",
    val folderUri: String? = null,
    val sort: TrackSort = TrackSort.Title,
    val ascending: Boolean = true,
)

/** Shared, deterministic query semantics. Call off the main thread for large libraries. */
object LibraryQueries {
    fun tracks(snapshot: LibrarySnapshot, query: LibraryQuery, checkActive: () -> Unit = {}): List<Track> =
        rows(snapshot.tracks.map { SongRow(it.id, it) }, query, checkActive).map { it.track }

    fun rows(rows: List<SongRow>, query: LibraryQuery, checkActive: () -> Unit = {}): List<SongRow> {
        var steps = 0
        fun checkpoint() { if (steps++ % 128 == 0) checkActive() }
        val terms = query.text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = rows.asSequence().mapNotNull { row ->
            checkpoint()
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
        }
        val result = if (query.sort == TrackSort.Manual) filtered.toList() else {
            fun <T : Comparable<T>> compare(a: T?, b: T?) = compareKnown(a, b, query.ascending)
            fun key(value: String?) = value?.lowercase(Locale.ROOT)
            // Keys belong to this request's selected rows, never to track or occurrence IDs.
            class SortRow(val row: SongRow, val title: String, val text: String?, val albumArtist: String?)
            val prepared = filtered.map { row ->
                checkpoint()
                val track = row.track
                val title = track.title.lowercase(Locale.ROOT)
                SortRow(row, title, when (query.sort) {
                    TrackSort.Title -> title
                    TrackSort.Artist -> key(track.artist)
                    TrackSort.AlbumArtist -> key(track.albumArtist ?: track.artist)
                    TrackSort.Album -> key(track.album)
                    TrackSort.Folder -> key(track.relativePath)
                    TrackSort.Composer -> key(track.composer)
                    TrackSort.Genre -> key(track.genre)
                    else -> null
                }, if (query.sort == TrackSort.Album) key(track.albumArtist ?: track.artist) else null)
            }
            prepared.sortedWith { a, b ->
                checkpoint()
                val left = a.row.track
                val right = b.row.track
                val primary = when (query.sort) {
                    TrackSort.Manual, TrackSort.Count -> 0
                    TrackSort.Title, TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Folder,
                    TrackSort.Composer, TrackSort.Genre -> compare(a.text, b.text)
                    TrackSort.Album -> compare(a.text, b.text).takeIf { it != 0 }
                        ?: compare(a.albumArtist, b.albumArtist).takeIf { it != 0 }
                        ?: compare(left.discNumber, right.discNumber).takeIf { it != 0 }
                        ?: compare(left.trackNumber, right.trackNumber)
                    TrackSort.Year -> compare(left.year, right.year)
                    TrackSort.Duration -> compare(left.durationMs, right.durationMs)
                    TrackSort.Modified -> compare(left.modifiedAt, right.modifiedAt)
                    TrackSort.Added -> compare(left.addedAt, right.addedAt)
                    TrackSort.MostPlayed -> compare(left.playCount, right.playCount)
                }
                primary.takeIf { it != 0 } ?: a.title.compareTo(b.title).takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            }.map { it.row }.toList()
        }
        checkActive()
        return if (query.sort == TrackSort.Manual && !query.ascending) result.reversed() else result
    }
}

/** Unknown metadata stays last in either direction. */
internal fun <T : Comparable<T>> compareKnown(a: T?, b: T?, ascending: Boolean): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> a.compareTo(b) * if (ascending) 1 else -1
}
