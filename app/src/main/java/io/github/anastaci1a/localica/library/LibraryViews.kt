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
    fun groups(snapshot: LibrarySnapshot, view: SongListView, query: LibraryQuery, checkActive: () -> Unit = {}): List<LibraryGroup> =
        groups(snapshot, view, query, { LibraryQueries.tracks(snapshot, query, checkActive) }, checkActive)

    /** Count-dependent orders stay request-local; metadata-only plans belong to their source. */
    internal class Preparation(private val snapshot: LibrarySnapshot, private val checkActive: () -> Unit) {
        private val ordered = mutableMapOf<LibraryQuery, List<SongRow>>()
        fun groups(view: SongListView, query: LibraryQuery): List<LibraryGroup> =
            if (query.sort != TrackSort.MostPlayed) snapshot.groupingSource.groupPlans.groups(snapshot, view, query, checkActive)
            else groupRows(snapshot, view, query, {
                checkActive()
                ordered.getOrPut(query) {
                    LibraryQueries.tracks(snapshot, query, checkActive).map { SongRow(it.id, it) }
                }
            }, checkActive).map { LibraryGroup(it.id, it.title, it.subtitle, it.rows.map { row -> row.track }) }
    }

    /** Only explicit count overlays share this source-owned, bounded preparation. */
    internal class GroupPlans(private val source: LibrarySnapshot) {
        private val lock = Any()
        private val ordered = linkedMapOf<LibraryQuery, List<SongRow>>()
        private val grouped = linkedMapOf<Pair<SongListView, LibraryQuery>, List<GroupRows>>()

        fun groups(current: LibrarySnapshot, view: SongListView, query: LibraryQuery,
            checkActive: () -> Unit): List<LibraryGroup> {
            checkActive()
            require(current.groupingSource === source && query.sort != TrackSort.MostPlayed)
            val key = view to query
            val plan = synchronized(lock) { grouped[key] } ?: groupRows(source, view, query, {
                checkActive()
                synchronized(lock) { ordered[query] } ?: LibraryQueries.rows(
                    source.tracks.mapIndexed { index, track ->
                        if (index % 128 == 0) checkActive()
                        SongRow(index.toString(), track)
                    }, query, checkActive).also { rows ->
                    checkActive()
                    synchronized(lock) {
                        if (rows.size <= 40_000) {
                            ordered[query] = rows
                            while (ordered.size > 12 || ordered.values.sumOf { it.size } > 40_000)
                                ordered.remove(ordered.keys.first())
                        }
                    }
                }
            }, checkActive).also { groups ->
                checkActive()
                synchronized(lock) {
                    if (groups.sumOf { it.rows.size } <= 40_000) {
                        grouped[key] = groups
                        while (grouped.size > 12 || grouped.values.sumOf { list -> list.sumOf { it.rows.size } } > 40_000)
                            grouped.remove(grouped.keys.first())
                    }
                }
            }
            var visited = 0
            val result = plan.map { group ->
                LibraryGroup(group.id, group.title, group.subtitle, group.rows.map { row ->
                    if (visited++ % 128 == 0) checkActive()
                    if (current === source) row.track else {
                        val track = current.tracks[row.id.toInt()]
                        if (view == SongListView.Folders || query.folderUri != null)
                            track.copy(uri = row.track.uri, relativePath = row.track.relativePath)
                        else track
                    }
                })
            }
            checkActive()
            return result
        }
    }

    private fun groups(snapshot: LibrarySnapshot, view: SongListView, query: LibraryQuery,
        ordered: () -> List<Track>, checkActive: () -> Unit): List<LibraryGroup> =
        groupRows(snapshot, view, query, { ordered().map { SongRow(it.id, it) } }, checkActive)
            .map { LibraryGroup(it.id, it.title, it.subtitle, it.rows.map { row -> row.track }) }

    private data class GroupRows(val id: String, val title: String, val subtitle: String?, val rows: List<SongRow>)

    private fun groupRows(snapshot: LibrarySnapshot, view: SongListView, query: LibraryQuery,
        ordered: () -> List<SongRow>, checkActive: () -> Unit): List<GroupRows> {
        if (view == SongListView.Songs || view == SongListView.Playlists || view == SongListView.Search) return emptyList()
        val tracks = ordered()
        fun key(text: String?) = text?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        val buckets = linkedMapOf<List<String?>, MutableList<SongRow>>()
        val labels = mutableMapOf<List<String?>, Pair<String, String?>>()
        fun add(id: List<String?>, title: String, subtitle: String?, row: SongRow) {
            buckets.getOrPut(id) { mutableListOf() }.add(row)
            labels.putIfAbsent(id, title to subtitle)
        }
        for ((index, row) in tracks.withIndex()) {
            if (index % 128 == 0) checkActive()
            val track = row.track
            when (view) {
                SongListView.Albums -> {
                    val artist = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
                    add(listOf(key(track.album), key(artist)), track.album?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        artist?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Album Artist", row)
                }
                SongListView.Artists -> add(listOf(key(track.artist)), track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", null, row)
                SongListView.AlbumArtists -> {
                    val artist = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
                    add(listOf(key(artist)), artist?.takeIf { it.isNotBlank() } ?: "Unknown Album Artist", null, row)
                }
                SongListView.Folders -> track.locations.filter { query.folderUri == null || it.folderUri == query.folderUri }.forEach { place ->
                    val parent = place.relativePath.substringBeforeLast('/', "")
                    val root = snapshot.folders.firstOrNull { it.uri == place.folderUri }?.name ?: place.folderUri
                    add(listOf(place.folderUri, parent), parent.ifEmpty { root }, if (parent.isEmpty()) place.folderUri else root,
                        row.copy(track = track.copy(uri = place.uri, relativePath = place.relativePath)))
                }
                else -> Unit
            }
        }
        checkActive()
        return buckets.map { (key, members) ->
            val label = labels.getValue(key)
            // Length prefixes distinguish missing tags, delimiters in names, and literal "Unknown" tags.
            GroupRows(key.joinToString("") { it?.let { value -> "${value.length}:$value" } ?: "-1:" },
                label.first, label.second, members.distinctBy { it.track.id })
        }.sortedWith(compareBy<GroupRows> { it.title.lowercase(Locale.ROOT) }.thenBy { it.subtitle }.thenBy { it.id })
    }
}
