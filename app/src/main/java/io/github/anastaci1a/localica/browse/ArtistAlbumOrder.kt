package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import java.util.Locale

/** One album order owns both the carousel and its contiguous occurrence rows.
 * This does not choose defaults, filter selected albums, or change saved preferences. */
internal object ArtistAlbumOrder {
    data class Group(val album: LibraryGroup, val rows: List<SongRow>)

    sealed interface Result
    data class Ordered(val groups: List<Group>) : Result {
        val albums: List<LibraryGroup> = groups.map { it.album }
        val rows: List<SongRow> = groups.flatMap { it.rows }
    }
    data class Unsupported(val sort: TrackSort) : Result

    // Manual occurrence order and a multi-file album's Folder key have no album aggregate.
    val supportedSorts: Set<TrackSort> = setOf(TrackSort.Title, TrackSort.Album, TrackSort.Year,
        TrackSort.Count, TrackSort.Duration, TrackSort.Added, TrackSort.Modified, TrackSort.MostPlayed,
        TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre)

    /** Groups use LibraryViews' length-prefixed album/Album Artist identity, not title alone.
     * Disc and track are always ascending (unknown last), then lowercase song title, track ID,
     * occurrence ID and input ordinal. Reversing the outer sort never reverses those rows.
     *
     * Year/Added use the earliest known value, Modified the latest. Duration sums known values
     * (all missing is unknown); Count and MostPlayed use distinct track IDs, as album catalogs do.
     * Text aggregates retain the catalog's distinct/sorted joined values; blank-only is unknown.
     * Title/Album use the album title, with a missing tag distinct from a literal "Unknown album".
     * Missing aggregates stay last in both directions; ties always use lowercase album title and
     * canonical album ID ascending. Unknown Manual/Folder policy is returned, never guessed.
     */
    fun order(rows: List<SongRow>, sort: TrackSort, ascending: Boolean,
        checkActive: () -> Unit = {}): Result {
        checkActive()
        if (sort !in supportedSorts) return Unsupported(sort)
        val source = rows.toList()
        var work = 0
        fun checkpoint() { if (work++ % 128 == 0) checkActive() }

        // LibraryViews deliberately deduplicates track IDs. Private ordinal IDs let it perform
        // the SAME canonical grouping without losing input occurrences or joining unrelated
        // snapshots sharing a track ID. Only the original rows/tracks escape this projection.
        val projection = source.mapIndexed { index, row ->
            checkpoint()
            row.track.copy(id = index.toString())
        }
        val canonical = LibraryViews.groups(LibrarySnapshot(tracks = projection), SongListView.Albums,
            LibraryQuery(sort = TrackSort.Manual), checkActive)
        val inner = Comparator<IndexedValue<SongRow>> { a, b ->
            checkpoint()
            val first = a.value.track
            val second = b.value.track
            compareKnown(first.discNumber, second.discNumber, true).takeIf { it != 0 }
                ?: compareKnown(first.trackNumber, second.trackNumber, true).takeIf { it != 0 }
                ?: first.title.lowercase(Locale.ROOT).compareTo(second.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: first.id.compareTo(second.id).takeIf { it != 0 }
                ?: a.value.id.compareTo(b.value.id).takeIf { it != 0 }
                ?: a.index.compareTo(b.index)
        }
        val groups = canonical.map { album ->
            checkpoint()
            val members = album.tracks.map { track ->
                val index = track.id.toInt()
                IndexedValue(index, source[index])
            }.sortedWith(inner).map { it.value }
            val original = album.copy(tracks = members.map { it.track }.distinctBy { it.id })
            val group = Group(original, members)
            Keyed(group, aggregate(group, sort))
        }.sortedWith { a, b ->
            checkpoint()
            compareKnown(a.key.number, b.key.number, ascending).takeIf { it != 0 }
                ?: compareKnown(a.key.text, b.key.text, ascending).takeIf { it != 0 }
                ?: a.group.album.title.lowercase(Locale.ROOT)
                    .compareTo(b.group.album.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: a.group.album.id.compareTo(b.group.album.id)
        }.map { it.group }
        checkActive()
        return Ordered(groups)
    }

    private data class Key(val number: Long? = null, val text: String? = null)
    private data class Keyed(val group: Group, val key: Key)

    private fun aggregate(group: Group, sort: TrackSort): Key {
        val tracks = group.album.tracks
        fun text(value: String?) = Key(text = value?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT))
        return when (sort) {
            TrackSort.Title, TrackSort.Album -> text(tracks.firstOrNull()?.album?.trim())
            TrackSort.Year -> Key(number = group.rows.mapNotNull { it.track.year }.minOrNull()?.toLong())
            TrackSort.Added -> Key(number = group.rows.mapNotNull { it.track.addedAt }.minOrNull())
            TrackSort.Modified -> Key(number = group.rows.mapNotNull { it.track.modifiedAt }.maxOrNull())
            TrackSort.Duration -> Key(number = tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum())
            TrackSort.Count -> Key(number = tracks.size.toLong())
            TrackSort.MostPlayed -> Key(number = tracks.sumOf { it.playCount })
            TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre -> text(tracks.mapNotNull {
                when (sort) {
                    TrackSort.Artist -> it.artist
                    TrackSort.AlbumArtist -> it.albumArtist ?: it.artist
                    TrackSort.Composer -> it.composer
                    TrackSort.Genre -> it.genre
                    else -> null
                }?.takeIf { value -> value.isNotBlank() }
            }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(", "))
            TrackSort.Manual, TrackSort.Folder -> error("Unsupported album aggregate: $sort")
        }
    }
}
