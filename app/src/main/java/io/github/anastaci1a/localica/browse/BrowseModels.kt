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
    val playCount: Long = songs.distinctBy { it.track.id }.sumOf { it.track.playCount }, val coverUri: String? = null) {
    val key: String get() = row?.id ?: "${ref.kind}:${ref.id}"
    val coverUris: List<String> = collectionCoverUris(coverUri, songs.firstOrNull()?.track?.uri)
}

internal fun SongGrouping.label(row: SongRow): String = row.track.let { track -> when (this) {
    SongGrouping.Artist -> track.artist
    SongGrouping.AlbumArtist -> track.albumArtist ?: track.artist
    SongGrouping.Composer -> track.composer
    SongGrouping.None -> null
} }?.takeIf { it.isNotBlank() } ?: "Unknown ${if (this == SongGrouping.AlbumArtist) label else label.lowercase(Locale.ROOT)}"

internal fun BrowseResult.groupAlbumSongs(grouping: SongGrouping, checkActive: () -> Unit = {}): BrowseResult {
    if (grouping == SongGrouping.None) return this
    var visits = 0
    val grouped = items.groupBy {
        if (visits++ % 128 == 0) checkActive()
        it.row?.let { row -> grouping.label(row) }.orEmpty()
    }.toSortedMap(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
    return copy(items = grouped.flatMap { (name, songs) -> songs.mapIndexed { index, item ->
        if (index % 128 == 0) checkActive()
        item.copy(groupHeading = name.takeIf { index == 0 })
    } })
}
data class BrowseResult(val items: List<BrowserItem> = emptyList(), val scope: SongListView = SongListView.Songs,
    val artistAlbums: List<LibraryGroup> = emptyList()) {
    val rows: List<SongRow> get() = items.mapNotNull { it.row }
}

/** One result owns carousel and row ordering; invalid old values use album fallback. */
internal fun orderedArtistAlbums(rows: List<SongRow>, query: LibraryQuery,
    checkActive: () -> Unit = {}): ArtistAlbumOrder.Ordered =
    when (val result = ArtistAlbumOrder.order(rows, query.sort, query.ascending, checkActive)) {
        is ArtistAlbumOrder.Ordered -> result
        is ArtistAlbumOrder.Unsupported -> ArtistAlbumOrder.order(rows, TrackSort.Album, true, checkActive) as ArtistAlbumOrder.Ordered
    }

/** UI-independent complete result snapshots; general queries run off Main. */
object BrowseQueries {
    private fun queueRows(queue: ListeningQueue, snapshot: LibrarySnapshot, currentTracks: Map<String, Track>) =
        queue.entries.map { SongRow(it.id, currentTracks[it.track.id] ?:
            it.track.copy(playCount = snapshot.trackPlays[it.track.id] ?: 0), it.sourcePlaylistId) }

    /** Opening-only physical queue projection: O(library tracks + selected queue entries).
     * No other queue catalog, sort or searchable-text preparation is performed.
     */
    internal fun openingQueue(snapshot: LibrarySnapshot, queue: ListeningQueue, route: BrowseRoute,
        occurrenceId: String): BrowseResult? {
        if (route.search || route.globalSearch || route.text.isNotBlank() || route.filter != null ||
            route.collection != MediaRef(MediaKind.Queue, queue.id) || queue.entries.none { it.id == occurrenceId }) return null
        val rows = queueRows(queue, snapshot, snapshot.tracksById)
        return BrowseResult(rows.map { row -> BrowserItem(MediaRef(MediaKind.Song, row.track.id),
            row.track.title, row.track.artist ?: row.track.albumArtist, listOf(row), row) }, scope(route))
    }

    private data class Catalog(val snapshot: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val queues: List<ListeningQueue>, val route: BrowseRoute, val query: LibraryQuery,
        val kinds: Set<MediaKind>, val sorts: BrowseSortSnapshot, val result: BrowseResult, val search: List<String>)
    private val catalogs = ArrayDeque<Catalog>()
    private var catalogSource: LibrarySnapshot? = null
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
        MediaKind.Artist, MediaKind.AlbumArtist -> LibraryQuery(sort = TrackSort.Year)
        MediaKind.Playlist, MediaKind.Queue -> LibraryQuery(sort = TrackSort.Manual)
        else -> LibraryQuery()
    }
    fun evaluate(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>, route: BrowseRoute,
        query: LibraryQuery? = null, queues: List<ListeningQueue> = emptyList(),
        allCollections: Set<MediaKind> = LibraryFilters().allCollections,
        sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default, checkActive: () -> Unit = {}): BrowseResult {
        checkActive()
        val effectiveQuery = query ?: sorts.query(route)
        // Navigation/session identity and draft state do not change the computed catalog.
        val baseRoute = route.copy(text = "", key = "", title = null, queryCommitted = false,
            querySort = null, queryAscending = null,
            tab = if (route.globalSearch && route.search) MainTab.Library else route.tab,
            collection = route.collection.takeUnless { route.globalSearch && route.search })
        val baseQuery = effectiveQuery.copy(text = "")
        val relevantQueues = if (!route.globalSearch && (route.collection?.kind == MediaKind.Queue || route.tab == MainTab.Queues)) queues else emptyList()
        val cached = synchronized(catalogs) {
            if (catalogSource !== snapshot) {
                catalogs.clear()
                catalogSource = snapshot
            }
            catalogs.firstOrNull {
                it.playlists == playlists && it.queues == relevantQueues &&
                    it.route == baseRoute && it.query == baseQuery && it.kinds == allCollections && it.sorts.sameCollections(sorts)
            }
        }
        val catalog = cached ?: run {
            val result = evaluateCatalog(snapshot, playlists, baseRoute, baseQuery, relevantQueues, allCollections, sorts, checkActive)
            val search = result.items.mapIndexed { index, item ->
                if (index % 128 == 0) checkActive()
                item.row?.track?.let { track ->
                    snapshot.searchIndex?.text(track, effectiveQuery.folderUri) ?: LibrarySearchIndex.text(track, effectiveQuery.folderUri)
                } ?: (item.title + "\n" + item.subtitle.orEmpty()).lowercase(Locale.ROOT)
            }
            checkActive()
            Catalog(snapshot, playlists, relevantQueues, baseRoute, baseQuery, allCollections.toSet(), sorts, result, search).also { entry ->
                synchronized(catalogs) {
                    // A superseded preparation can finish, but must not evict the current generation.
                    if (catalogSource === snapshot) {
                        catalogs.addFirst(entry)
                        while (catalogs.size > 1 && (catalogs.size > 12 || catalogs.sumOf { it.result.items.size } > 40_000)) catalogs.removeLast()
                    }
                }
            }
        }
        val terms = route.text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        val result = if (terms.isEmpty()) catalog.result else catalog.result.copy(items = catalog.result.items.filterIndexed { index, _ ->
            if (index % 128 == 0) checkActive()
            terms.all { it in catalog.search[index] }
        })
        return if (route.collection?.kind == MediaKind.Album && !route.globalSearch) result.groupAlbumSongs(sorts.grouping(route), checkActive) else result
    }

    private fun evaluateCatalog(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>, route: BrowseRoute,
        query: LibraryQuery, queues: List<ListeningQueue>, allCollections: Set<MediaKind>, sorts: BrowseSortSnapshot, checkActive: () -> Unit): BrowseResult {
        val effective = query.copy(text = route.text)
        val collection = route.collection.takeUnless { route.globalSearch }
        val currentTracks by lazy { snapshot.tracksById }
        val groupPreparation = LibraryViews.Preparation(snapshot, checkActive)
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
            return groupPreparation.groups(view, LibraryViews.defaults(view)).map { group ->
                val ref = MediaRef(kind, group.id)
                val rows = group.tracks.map { SongRow(it.id, it) }
                BrowserItem(ref, group.title, group.subtitle ?: songCountLabel(group.tracks.size),
                    if (collection == null) sorts.rows(ref, rows, checkActive) else rows)
            }
        }
        fun playlistItems() = playlists.map { playlist -> BrowserItem(MediaRef(MediaKind.Playlist, playlist.id), playlist.name,
            songCountLabel(playlist.entries.size), PlaylistEdits.rows(playlist, snapshot, LibraryQuery(sort = TrackSort.Manual), currentTracks),
            addedAt = playlist.createdAt, modifiedAt = playlist.modifiedAt, playCount = snapshot.playlistPlays[playlist.id] ?: 0, coverUri = playlist.coverUri) }
        fun catalog(kind: MediaKind): List<BrowserItem> = when (kind) {
            MediaKind.Song -> songItems(LibraryQueries.rows(snapshot.tracks.map { SongRow(it.id, it) }, effective, checkActive))
            MediaKind.Playlist -> playlistItems()
            MediaKind.Queue -> {
                queues.map { queue -> BrowserItem(MediaRef(MediaKind.Queue, queue.id), queue.name,
                    songCountLabel(queue.entries.size), queueRows(queue, snapshot, currentTracks)) }
            }
            else -> groups(kind)
        }
        if (collection != null) {
            // Detail routes need only this collection's occurrences, not every saved queue/playlist.
            val source = when (collection.kind) {
                MediaKind.Playlist -> playlists.firstOrNull { it.id == collection.id }?.let {
                    PlaylistEdits.rows(it, snapshot, LibraryQuery(sort = TrackSort.Manual), currentTracks)
                }.orEmpty()
                MediaKind.Queue -> queues.firstOrNull { it.id == collection.id }?.let {
                    queueRows(it, snapshot, currentTracks)
                }.orEmpty()
                else -> catalog(collection.kind).firstOrNull { it.ref == collection }?.songs.orEmpty()
            }
            val order = if (collection.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) effective.copy(sort = TrackSort.Manual, ascending = true) else effective
            if (collection.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
                val grouped = orderedArtistAlbums(source, order, checkActive)
                return BrowseResult(songItems(grouped.rows), scope(route), grouped.albums)
            }
            return BrowseResult(songItems(LibraryQueries.rows(source, order, checkActive)), scope(route))
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
        val items = kinds.flatMap { kind -> checkActive(); catalog(kind) }.filter { item ->
            if (route.tab == MainTab.MostPlayed && !route.globalSearch && item.playCount == 0L) false
            else if (item.row != null) true
            else terms.all { it in (item.title + "\n" + item.subtitle.orEmpty()).lowercase(Locale.ROOT) }
        }
        if (kinds == listOf(MediaKind.Song)) return BrowseResult(items, scope(route))
        val ordered = orderCollectionItems(items, effective, checkActive)
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

internal fun BrowserItem.information(style: BrowseStyle, inherited: Map<MediaKind, BrowseStyle> = emptyMap()): String? {
    if (style.extraInfo == ExtraInfo.Inherited) {
        val own = inherited[ref.kind] ?: BrowseStyle()
        return information(own.copy(extraInfo = own.extraInfo.takeUnless { it == ExtraInfo.Inherited } ?: ExtraInfo.Automatic))
    }
    val info = if (style.extraInfo != ExtraInfo.Automatic) style.extraInfo else
        ExtraInfo.entries.firstOrNull { it.name == style.sort.name } ?: return subtitle
    return when (info) {
        ExtraInfo.None -> null
        ExtraInfo.Count -> songCountLabel(songs.size)
        ExtraInfo.Duration -> duration()?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}${if (songs.any { row -> row.track.durationMs == null }) " (partial)" else ""}" }
        ExtraInfo.Year -> songs.mapNotNull { it.track.year }.minOrNull()?.toString()
        ExtraInfo.MostPlayed -> "$playCount ${if (playCount == 1L) "play" else "plays"}"
        ExtraInfo.Added -> addedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Modified -> modifiedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Artist, ExtraInfo.AlbumArtist, ExtraInfo.Composer, ExtraInfo.Genre -> metadataText(TrackSort.valueOf(info.name))
        ExtraInfo.Automatic, ExtraInfo.Inherited -> subtitle
    }
}

internal fun songCountLabel(count: Int) = "$count ${if (count == 1) "song" else "songs"}"
