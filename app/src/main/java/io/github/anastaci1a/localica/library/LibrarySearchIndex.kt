package io.github.anastaci1a.localica.library

import java.util.Locale

/** Derived search text only. Ordering, live statistics and occurrence identity belong to the source. */
class LibrarySearchIndex internal constructor(
    val revision: Long,
    private val entries: Map<String, Entry>,
) {
    internal data class Fields(val title: String, val artist: String?, val album: String?, val albumArtist: String?,
        val genre: String?, val comment: String?, val composer: String?, val lyricist: String?, val year: Int?,
        val fileName: String, val paths: List<String>) {
        fun text() = (listOfNotNull(title, artist, album, albumArtist, genre, comment, composer, lyricist,
            year?.toString(), fileName) + paths).joinToString("\n").lowercase(Locale.ROOT)

        fun matches(track: Track, folderUri: String?) = title == track.title && artist == track.artist &&
            album == track.album && albumArtist == track.albumArtist && genre == track.genre && comment == track.comment &&
            composer == track.composer && lyricist == track.lyricist && year == track.year && fileName == track.fileName &&
            (if (folderUri != null) paths.size == 1 && paths[0] == track.relativePath
            else paths.size == track.locations.size + 1 && paths.last() == track.relativePath &&
                track.locations.indices.all { paths[it] == track.locations[it].relativePath })
    }
    internal data class Entry(val fields: Fields, val text: String)

    internal fun entry(id: String) = entries[id]
    internal val size get() = entries.size

    /** Copies/subsets and saved unavailable occurrences may carry different metadata under the same ID. */
    fun text(track: Track, folderUri: String? = null): String {
        return entries[track.id]?.takeIf { it.fields.matches(track, folderUri) }?.text ?: fields(track, folderUri).text()
    }

    companion object {
        internal fun fields(track: Track, folderUri: String? = null) = Fields(
            track.title, track.artist, track.album, track.albumArtist, track.genre, track.comment,
            track.composer, track.lyricist, track.year, track.fileName,
            if (folderUri == null) track.locations.map { it.relativePath } + track.relativePath else listOf(track.relativePath),
        )
        internal fun text(track: Track, folderUri: String? = null) = fields(track, folderUri).text()
    }
}

/** One builder per library store; retains only the last prepared generation. Call off Main.
 * Revisions identify preparation order, not database transaction order; each returned index is self-contained. */
internal class LibrarySearchIndexBuilder {
    private var current = LibrarySearchIndex(0, emptyMap())

    @Synchronized
    fun prepare(snapshot: LibrarySnapshot, checkActive: () -> Unit = {}): LibrarySnapshot {
        val previous = current
        var changed = previous.size != snapshot.tracks.size
        val entries = HashMap<String, LibrarySearchIndex.Entry>(snapshot.tracks.size)
        snapshot.tracks.forEachIndexed { index, track ->
            if (index % 128 == 0) checkActive()
            val fields = LibrarySearchIndex.fields(track)
            val entry = previous.entry(track.id)?.takeIf { it.fields == fields }
                ?: LibrarySearchIndex.Entry(fields, fields.text()).also { changed = true }
            entries[track.id] = entry
        }
        checkActive()
        val next = if (changed) LibrarySearchIndex(previous.revision + 1, entries) else previous
        current = next
        return snapshot.copy(searchIndex = next)
    }
}
