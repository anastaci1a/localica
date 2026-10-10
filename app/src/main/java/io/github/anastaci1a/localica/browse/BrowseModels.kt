package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.ListeningQueue
import java.util.Locale

data class BrowserItem(val ref: MediaRef, val title: String, val subtitle: String?, val songs: List<SongRow>,
    val row: SongRow? = null) {
    val key: String get() = row?.id ?: "${ref.kind}:${ref.id}"
    val coverUris: List<String> = songs.sortedBy { it.track.id }.map { it.track.uri }.distinct()
}
data class BrowseResult(val items: List<BrowserItem> = emptyList(), val scope: SongListView = SongListView.Songs) {
    val rows: List<SongRow> get() = items.mapNotNull { it.row }
}

/** UI-independent complete result snapshots, evaluated off the main thread. */
object BrowseQueries {
    val libraryKinds = MediaKind.entries.filterNot { it == MediaKind.Queue }
    fun scope(route: BrowseRoute): SongListView = if (route.search) SongListView.Search else when (route.collection?.kind) {
        MediaKind.Album -> SongListView.Albums
        MediaKind.Artist -> SongListView.Artists
        MediaKind.AlbumArtist -> SongListView.AlbumArtists
        MediaKind.Folder -> SongListView.Folders
        MediaKind.Playlist -> SongListView.Playlists
        else -> when (route.tab) {
            MainTab.Albums -> SongListView.Albums
            MainTab.Artists -> SongListView.Artists
            MainTab.AlbumArtists -> SongListView.AlbumArtists
            MainTab.Folders -> SongListView.Folders
            MainTab.Playlists -> SongListView.Playlists
            else -> SongListView.Songs
        }
    }
    fun defaultQuery(route: BrowseRoute): LibraryQuery = if (route.search) LibraryQuery() else when (route.collection?.kind) {
        MediaKind.Album -> LibraryQuery(sort = TrackSort.Album)
        MediaKind.Playlist, MediaKind.Queue -> LibraryQuery(sort = TrackSort.Manual)
        else -> LibraryQuery()
    }
    fun evaluate(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>, route: BrowseRoute,
        query: LibraryQuery = defaultQuery(route), queues: List<ListeningQueue> = emptyList()): BrowseResult {
        val effective = query.copy(text = if (route.search) route.text else "")
        fun songItems(rows: List<SongRow>) = rows.map { BrowserItem(MediaRef(MediaKind.Song, it.track.id),
            it.track.title, it.track.artist ?: it.track.albumArtist, listOf(it), it) }
        fun groups(kind: MediaKind): List<BrowserItem> {
            val view = when (kind) {
                MediaKind.Album -> SongListView.Albums
                MediaKind.Artist -> SongListView.Artists
                MediaKind.AlbumArtist -> SongListView.AlbumArtists
                MediaKind.Folder -> SongListView.Folders
                else -> return emptyList()
            }
            return LibraryViews.groups(snapshot, view, LibraryViews.defaults(view)).map { group ->
                BrowserItem(MediaRef(kind, group.id), group.title, group.subtitle ?: "${group.tracks.size} songs",
                    group.tracks.map { SongRow(it.id, it) })
            }
        }
        fun playlistItems() = playlists.map { playlist -> BrowserItem(MediaRef(MediaKind.Playlist, playlist.id), playlist.name,
            "${playlist.entries.size} songs", PlaylistEdits.rows(playlist, snapshot, LibraryViews.defaults(SongListView.Playlists))) }
        fun catalog(kind: MediaKind): List<BrowserItem> = when (kind) {
            MediaKind.Song -> songItems(LibraryQueries.rows(snapshot.tracks.map { SongRow(it.id, it) }, effective))
            MediaKind.Playlist -> playlistItems()
            MediaKind.Queue -> {
                val current = snapshot.tracks.associateBy { it.id }
                queues.map { queue -> BrowserItem(MediaRef(MediaKind.Queue, queue.id), queue.name,
                    "${queue.entries.size} songs", queue.entries.map { SongRow(it.id, current[it.track.id] ?: it.track) }) }
            }
            else -> groups(kind)
        }
        val collection = route.collection.takeUnless { route.globalSearch }
        if (collection != null) {
            val source = catalog(collection.kind).firstOrNull { it.ref == collection }?.songs.orEmpty()
            return BrowseResult(songItems(LibraryQueries.rows(source, effective)), scope(route))
        }
        val kinds = if (route.globalSearch) route.filter?.let(::listOf) ?: libraryKinds else when (route.tab) {
            MainTab.Library -> route.filter?.let(::listOf) ?: listOf(MediaKind.Album, MediaKind.Playlist)
            MainTab.Songs -> listOf(MediaKind.Song)
            MainTab.Albums -> listOf(MediaKind.Album)
            MainTab.Artists -> listOf(MediaKind.Artist)
            MainTab.AlbumArtists -> listOf(MediaKind.AlbumArtist)
            MainTab.Folders -> listOf(MediaKind.Folder)
            MainTab.Playlists -> listOf(MediaKind.Playlist)
            else -> emptyList()
        }
        val terms = effective.text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val items = kinds.flatMap(::catalog).filter { item ->
            if (item.row != null) true
            else terms.all { it in (item.title + "\n" + item.subtitle.orEmpty()).lowercase(Locale.ROOT) }
        }
        if (kinds == listOf(MediaKind.Song)) return BrowseResult(items, scope(route))
        val ordered = items.sortedWith(compareBy<BrowserItem> { it.title.lowercase(Locale.ROOT) }.thenBy { it.key })
        return BrowseResult(if (effective.ascending) ordered else ordered.reversed(), scope(route))
    }
}
