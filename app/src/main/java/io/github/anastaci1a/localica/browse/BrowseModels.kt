package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.ListeningQueue
import java.util.Locale

data class BrowserItem(val ref: MediaRef, val title: String, val subtitle: String?, val songs: List<SongRow>,
    val row: SongRow? = null, val groupHeading: String? = null,
    val addedAt: Long? = songs.mapNotNull { it.track.addedAt }.minOrNull(),
    val modifiedAt: Long? = songs.mapNotNull { it.track.modifiedAt }.maxOrNull(),
    val playCount: Long = songs.distinctBy { it.track.id }.sumOf { it.track.playCount }) {
    val key: String get() = row?.id ?: "${ref.kind}:${ref.id}"
    val coverUris: List<String> = songs.sortedBy { it.track.id }.map { it.track.uri }.distinct()
}

internal fun BrowseResult.groupAlbumSongs(grouping: SongGrouping): BrowseResult {
    if (grouping == SongGrouping.None) return this
    fun label(item: BrowserItem): String = item.row?.track?.let { track -> when (grouping) {
        SongGrouping.Artist -> track.artist
        SongGrouping.AlbumArtist -> track.albumArtist ?: track.artist
        SongGrouping.Composer -> track.composer
        SongGrouping.None -> null
    } }?.takeIf { it.isNotBlank() } ?: "Unknown ${if (grouping == SongGrouping.AlbumArtist) grouping.label else grouping.label.lowercase(Locale.ROOT)}"
    val grouped = items.groupBy(::label).toSortedMap(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
    return copy(items = grouped.flatMap { (name, songs) -> songs.mapIndexed { index, item -> item.copy(groupHeading = name.takeIf { index == 0 }) } })
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
    fun defaultQuery(route: BrowseRoute): LibraryQuery = if (route.search) LibraryQuery() else if (route.tab == MainTab.MostPlayed && route.collection == null)
        LibraryQuery(sort = TrackSort.MostPlayed, ascending = false) else when (route.collection?.kind) {
        MediaKind.Album -> LibraryQuery(sort = TrackSort.Album)
        MediaKind.Playlist, MediaKind.Queue -> LibraryQuery(sort = TrackSort.Manual)
        else -> LibraryQuery()
    }
    fun evaluate(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>, route: BrowseRoute,
        query: LibraryQuery = defaultQuery(route), queues: List<ListeningQueue> = emptyList(), allCollections: Set<MediaKind> = LibraryFilters().allCollections): BrowseResult {
        val effective = query.copy(text = route.text)
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
            "${playlist.entries.size} songs", PlaylistEdits.rows(playlist, snapshot, LibraryViews.defaults(SongListView.Playlists)),
            addedAt = playlist.createdAt, modifiedAt = playlist.modifiedAt, playCount = snapshot.playlistPlays[playlist.id] ?: 0) }
        fun catalog(kind: MediaKind): List<BrowserItem> = when (kind) {
            MediaKind.Song -> songItems(LibraryQueries.rows(snapshot.tracks.map { SongRow(it.id, it) }, effective))
            MediaKind.Playlist -> playlistItems()
            MediaKind.Queue -> {
                val current = snapshot.tracks.associateBy { it.id }
                queues.map { queue -> BrowserItem(MediaRef(MediaKind.Queue, queue.id), queue.name,
                    "${queue.entries.size} songs", queue.entries.map { SongRow(it.id, current[it.track.id] ?: it.track.copy(playCount = snapshot.trackPlays[it.track.id] ?: 0), it.sourcePlaylistId) }) }
            }
            else -> groups(kind)
        }
        val collection = route.collection.takeUnless { route.globalSearch }
        if (collection != null) {
            val source = catalog(collection.kind).firstOrNull { it.ref == collection }?.songs.orEmpty()
            return BrowseResult(songItems(LibraryQueries.rows(source, effective)), scope(route))
        }
        val kinds = if (route.globalSearch) route.filter?.let(::listOf) ?: libraryKinds else when (route.tab) {
            MainTab.Library -> route.filter?.let(::listOf) ?: libraryKinds.filter { it in allCollections && it != MediaKind.Song }
            MainTab.Songs, MainTab.MostPlayed -> listOf(MediaKind.Song)
            MainTab.Albums -> listOf(MediaKind.Album)
            MainTab.Artists -> listOf(MediaKind.Artist)
            MainTab.AlbumArtists -> listOf(MediaKind.AlbumArtist)
            MainTab.Folders -> listOf(MediaKind.Folder)
            MainTab.Playlists -> listOf(MediaKind.Playlist)
            else -> emptyList()
        }
        val terms = effective.text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val items = kinds.flatMap(::catalog).filter { item ->
            if (route.tab == MainTab.MostPlayed && !route.globalSearch && item.playCount == 0L) false
            else if (item.row != null) true
            else terms.all { it in (item.title + "\n" + item.subtitle.orEmpty()).lowercase(Locale.ROOT) }
        }
        if (kinds == listOf(MediaKind.Song)) return BrowseResult(items, scope(route))
        fun <T : Comparable<T>> compare(a: T?, b: T?) = compareKnown(a, b, effective.ascending)
        val ordered = items.sortedWith { a, b ->
            val primary = when (effective.sort) {
                TrackSort.Count -> compare(a.songs.size, b.songs.size)
                TrackSort.Duration -> compare(a.duration(), b.duration())
                TrackSort.Year -> compare(a.songs.mapNotNull { it.track.year }.minOrNull(), b.songs.mapNotNull { it.track.year }.minOrNull())
                TrackSort.Modified -> compare(a.modifiedAt, b.modifiedAt)
                TrackSort.Added -> compare(a.addedAt, b.addedAt)
                TrackSort.MostPlayed -> compare(a.playCount, b.playCount)
                TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre ->
                    compare(a.metadataText(effective.sort)?.lowercase(Locale.ROOT), b.metadataText(effective.sort)?.lowercase(Locale.ROOT))
                else -> compare(a.title.lowercase(Locale.ROOT), b.title.lowercase(Locale.ROOT))
            }
            primary.takeIf { it != 0 } ?: a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT)).takeIf { it != 0 } ?: a.key.compareTo(b.key)
        }
        return BrowseResult(ordered, scope(route))
    }
}

internal fun BrowserItem.duration(): Long? = songs.mapNotNull { it.track.durationMs }.takeIf { it.isNotEmpty() }?.sum()
internal fun BrowserItem.metadataText(sort: TrackSort): String? = songs.mapNotNull {
    when (sort) {
        TrackSort.Artist -> it.track.artist; TrackSort.AlbumArtist -> it.track.albumArtist ?: it.track.artist
        TrackSort.Composer -> it.track.composer; TrackSort.Genre -> it.track.genre; else -> null
    }
}.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(", ")

internal fun BrowserItem.information(style: BrowseStyle): String? {
    val info = if (style.extraInfo != ExtraInfo.Automatic) style.extraInfo else
        ExtraInfo.entries.firstOrNull { it.name == style.sort.name } ?: return subtitle
    return when (info) {
        ExtraInfo.None -> null
        ExtraInfo.Count -> "${songs.size} songs"
        ExtraInfo.Duration -> duration()?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}${if (songs.any { row -> row.track.durationMs == null }) " (partial)" else ""}" }
        ExtraInfo.Year -> songs.mapNotNull { it.track.year }.minOrNull()?.toString()
        ExtraInfo.MostPlayed -> "$playCount ${if (playCount == 1L) "play" else "plays"}"
        ExtraInfo.Added -> addedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Modified -> modifiedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Artist, ExtraInfo.AlbumArtist, ExtraInfo.Composer, ExtraInfo.Genre -> metadataText(TrackSort.valueOf(info.name))
        ExtraInfo.Automatic -> subtitle
    }
}
