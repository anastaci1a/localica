package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.statistics.saturatedAdd
import java.util.Locale

data class BrowserItem(val ref: MediaRef, val title: String, val subtitle: String?, val songs: List<SongRow>,
    val row: SongRow? = null, val groupHeading: String? = null, private val added: Long? = null,
    private val modified: Long? = null, private val plays: Long? = null, val coverUri: String? = null) {
    val key: String get() = row?.id ?: "${ref.kind}:${ref.id}"
    val coverUris: List<String> = collectionCoverUris(coverUri, songs.firstOrNull()?.track?.uri)
    // Folded on first read, not on construction: a queue item's songs are a view of the queue (#957).
    internal val facts by lazy(LazyThreadSafetyMode.PUBLICATION) { SongFacts(songs) }
    val addedAt get() = added ?: facts.addedAt
    val modifiedAt get() = modified ?: facts.modifiedAt
    val playCount get() = plays ?: facts.plays
}

/** One pass over a collection's songs; plays and listening count each library song once. */
internal class SongFacts(songs: List<SongRow>) {
    var addedAt: Long? = null; var modifiedAt: Long? = null; var year: Int? = null; var durationMs: Long? = null
    var unknownDuration = false; var unknownListened = false; var plays = 0L; var listenedMs = 0L
    init {
        val seen = if (songs.size > 1) HashSet<String>() else null
        for (track in songs.asSequence().map { it.track }) {
            track.addedAt?.let { addedAt = minOf(it, addedAt ?: it) }
            track.modifiedAt?.let { modifiedAt = maxOf(it, modifiedAt ?: it) }
            track.year?.let { year = minOf(it, year ?: it) }
            track.durationMs?.let { durationMs = (durationMs ?: 0L) + it } ?: run { unknownDuration = true }
            if (track.playCount > 0 && track.durationMs == null) unknownListened = true
            if (seen?.add(track.id) != false) { plays += track.playCount; listenedMs = saturatedAdd(listenedMs, track.longestListenedMs) }
        }
    }
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
    return BrowseResult(grouped.flatMap { (name, songs) -> songs.mapIndexed { index, item ->
        if (index % 128 == 0) checkActive()
        item.copy(groupHeading = name.takeIf { index == 0 })
    } }, scope, artistAlbums)
}
/** A song list is a view of its [rows]; only collection results hold built items. [keys] are the items'
 * keys, read without building an item or a row (#957). */
data class BrowseResult(val items: List<BrowserItem> = emptyList(), val scope: SongListView = SongListView.Songs,
    val artistAlbums: List<LibraryGroup> = emptyList(), val rows: List<SongRow> = items.mapNotNull { it.row },
    val keys: List<String> = items.mapView(BrowserItem::key)) {
    /** The last occurrence, as the former key map kept. */
    fun indexOfKey(key: Any) = keys.indexOfLast { it == key }
}
/** A pure projection of [source], an immutable revision: queue edits replace `entries`, and results own their rows.
 * Views of one projection over the same revision are equal without reading an element (#957). */
private class MappedList<S, T>(private val source: List<S>, private val f: (S) -> T) : AbstractList<T>(), RandomAccess {
    override val size get() = source.size
    override fun get(index: Int) = f(source[index])
    override fun equals(other: Any?) = if (other !is MappedList<*, *>) super.equals(other) else f == other.f &&
        (source === other.source || source is MappedList<*, *> && source == other.source)
    override fun hashCode() = size
}
internal fun <S, T> List<S>.mapView(f: (S) -> T): List<T> = MappedList(this, f)
internal fun songItem(row: SongRow) = BrowserItem(MediaRef(MediaKind.Song, row.track.id), row.track.title,
    row.track.artist ?: row.track.albumArtist, listOf(row), row)
internal fun songResult(rows: List<SongRow>, scope: SongListView, albums: List<LibraryGroup> = emptyList(),
    keys: List<String> = rows.mapView(SongRow::id)) = BrowseResult(rows.mapView(::songItem), scope, albums, rows, keys)
/** Matches by source index: 4 B per match, nothing for no match; a song list's matches stay a view of it. */
internal fun BrowseResult.matching(checkActive: () -> Unit, test: (Int) -> Boolean): BrowseResult {
    var hits = IntArray(0); var count = 0
    for (i in items.indices) {
        if (i % 128 == 0) checkActive()
        if (test(i)) { if (count == hits.size) hits = hits.copyOf(maxOf(16, count * 2)); hits[count++] = i }
    }
    checkActive()
    if (count == 0) return BrowseResult(scope = scope, artistAlbums = artistAlbums)
    val at = hits.copyOf(count).asList()
    return if (rows.size != items.size) BrowseResult(at.map(items::get), scope, artistAlbums)
        else songResult(at.mapView(rows::get), scope, artistAlbums, at.mapView(keys::get))
}

/** One result owns carousel and row ordering; invalid old values use album fallback. */
internal fun orderedArtistAlbums(rows: List<SongRow>, query: LibraryQuery,
    checkActive: () -> Unit = {}): ArtistAlbumOrder.Ordered =
    when (val result = ArtistAlbumOrder.order(rows, query.sort, query.ascending, checkActive)) {
        is ArtistAlbumOrder.Ordered -> result
        is ArtistAlbumOrder.Unsupported -> ArtistAlbumOrder.order(rows, TrackSort.Album, true, checkActive) as ArtistAlbumOrder.Ordered
    }

/** Artist rows with their carousel albums (#694). Grouped rows are contiguous album groups;
 * ungrouped rows use the ordinary song order across all albums while the carousel keeps the
 * album order. Unsupported old sorts use the same album fallback in both modes. */
internal data class ArtistRows(val rows: List<SongRow>, val albums: List<LibraryGroup>)

internal fun artistRows(rows: List<SongRow>, query: LibraryQuery, grouped: Boolean,
    checkActive: () -> Unit = {}): ArtistRows {
    val albums = orderedArtistAlbums(rows, query, checkActive)
    if (grouped) return ArtistRows(albums.rows, albums.albums)
    val songs = if (query.sort in ArtistAlbumOrder.supportedSorts) query else LibraryQuery(sort = TrackSort.Album)
    return ArtistRows(LibraryQueries.rows(rows, songs.copy(text = "", folderUri = null), checkActive), albums.albums)
}

/** UI-independent complete result snapshots; general queries run off Main. */
object BrowseQueries {
    /** Each occurrence with the library's current track; equal for one snapshot revision, so views compare by entries. */
    private class QueueRow(val snapshot: LibrarySnapshot) : (QueueEntry) -> SongRow {
        override fun invoke(entry: QueueEntry) = SongRow(entry.id, snapshot.tracksById[entry.track.id] ?:
            entry.track.copy(playCount = snapshot.trackPlays[entry.track.id] ?: 0), entry.sourcePlaylistId)
        override fun equals(other: Any?) = other is QueueRow && other.snapshot === snapshot
        override fun hashCode() = System.identityHashCode(snapshot)
    }
    private fun queueResult(queue: ListeningQueue, snapshot: LibrarySnapshot, scope: SongListView) =
        songResult(queue.entries.mapView(QueueRow(snapshot)), scope, keys = queue.entries.mapView(QueueEntry::id))

    /** Opening-only physical queue projection: the query's own view, without a catalog. */
    internal fun openingQueue(snapshot: LibrarySnapshot, queue: ListeningQueue, route: BrowseRoute,
        occurrenceId: String): BrowseResult? {
        if (route.search || route.globalSearch || route.text.isNotBlank() || route.filter != null ||
            route.collection != MediaRef(MediaKind.Queue, queue.id) || queue.entries.none { it.id == occurrenceId }) return null
        return queueResult(queue, snapshot, scope(route))
    }

    private data class Catalog(val route: BrowseRoute, val query: LibraryQuery,
        val kinds: Set<MediaKind>, val sorts: BrowseSortSnapshot, val result: BrowseResult)
    private val catalogs = ArrayDeque<Catalog>()
    private var catalogSource: LibrarySnapshot? = null
    private var catalogPlaylists: List<ManualPlaylist>? = null
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
        // Queue routes are views rebuilt in O(queues), never cached, so no queue revision is retained (#957).
        val queueRoute = !route.globalSearch && (route.collection?.kind == MediaKind.Queue || route.tab == MainTab.Queues)
        val cached = synchronized(catalogs) {
            // One generation: the latest requested library and playlists revisions.
            if (catalogSource !== snapshot || catalogPlaylists !== playlists) {
                catalogs.clear()
                catalogSource = snapshot; catalogPlaylists = playlists
            }
            if (queueRoute) null else catalogs.firstOrNull {
                it.route == baseRoute && it.query == baseQuery && it.kinds == allCollections && it.sorts.sameCollections(sorts)
            }
        }
        val catalog = cached ?: run {
            val result = evaluateCatalog(snapshot, playlists, baseRoute, baseQuery, if (queueRoute) queues else emptyList(), allCollections, sorts, checkActive)
            checkActive()
            Catalog(baseRoute, baseQuery, allCollections.toSet(), sorts, result).also { entry ->
                synchronized(catalogs) {
                    // A superseded preparation can finish, but must not evict the current generation.
                    if (catalogSource === snapshot && catalogPlaylists === playlists && !queueRoute) {
                        catalogs.addFirst(entry)
                        while (catalogs.size > 12) catalogs.removeLast()
                    }
                }
            }
        }
        val terms = route.text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        val result = if (terms.isEmpty()) catalog.result else catalog.result.run { matching(checkActive) { i ->
            val text = (if (rows.size == items.size) rows[i] else items[i].row)?.track?.let {
                snapshot.searchIndex?.text(it, effectiveQuery.folderUri) ?: LibrarySearchIndex.text(it, effectiveQuery.folderUri)
            } ?: items[i].let { (it.title + "\n" + it.subtitle.orEmpty()).lowercase(Locale.ROOT) }
            terms.all { it in text }
        } }
        return if (route.collection?.kind == MediaKind.Album && !route.globalSearch) result.groupAlbumSongs(sorts.grouping(route), checkActive) else result
    }

    private fun evaluateCatalog(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>, route: BrowseRoute,
        query: LibraryQuery, queues: List<ListeningQueue>, allCollections: Set<MediaKind>, sorts: BrowseSortSnapshot, checkActive: () -> Unit): BrowseResult {
        io.github.anastaci1a.localica.diagnostics.WorkCounters.catalogBuilds.incrementAndGet()
        val effective = query.copy(text = route.text)
        val collection = route.collection.takeUnless { route.globalSearch }
        val currentTracks by lazy { snapshot.tracksById }
        val groupPreparation = LibraryViews.Preparation(snapshot, checkActive)
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
            added = playlist.createdAt, modified = playlist.modifiedAt, plays = snapshot.playlistPlays[playlist.id] ?: 0, coverUri = playlist.coverUri) }
        fun catalog(kind: MediaKind): List<BrowserItem> = when (kind) {
            MediaKind.Song -> songResult(LibraryQueries.rows(snapshot.tracks.map { SongRow(it.id, it) }, effective, checkActive), scope(route)).items
            MediaKind.Playlist -> playlistItems()
            MediaKind.Queue -> {
                queues.map { queue -> BrowserItem(MediaRef(MediaKind.Queue, queue.id), queue.name,
                    songCountLabel(queue.entries.size), queue.entries.mapView(QueueRow(snapshot))) }
            }
            else -> groups(kind)
        }
        if (collection != null) {
            // Detail routes need only this collection's occurrences, not every saved queue/playlist.
            val source = when (collection.kind) {
                MediaKind.Playlist -> playlists.firstOrNull { it.id == collection.id }?.let {
                    PlaylistEdits.rows(it, snapshot, LibraryQuery(sort = TrackSort.Manual), currentTracks)
                }.orEmpty()
                MediaKind.Queue -> return queues.firstOrNull { it.id == collection.id }?.let { queueResult(it, snapshot, scope(route)) }
                    ?: songResult(emptyList(), scope(route))
                else -> catalog(collection.kind).firstOrNull { it.ref == collection }?.songs.orEmpty()
            }
            val order = if (collection.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) effective.copy(sort = TrackSort.Manual, ascending = true) else effective
            if (collection.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
                val artist = artistRows(source, order, sorts.artistAlbumGrouping, checkActive)
                return songResult(artist.rows, scope(route), artist.albums)
            }
            return songResult(LibraryQueries.rows(source, order, checkActive), scope(route))
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
        // evaluate strips text before catalog preparation and filters the completed search index.
        val items = kinds.flatMap { kind -> checkActive(); catalog(kind) }.filter { item ->
            route.tab != MainTab.MostPlayed || route.globalSearch || item.playCount != 0L
        }
        if (kinds == listOf(MediaKind.Song)) return BrowseResult(items, scope(route))
        val ordered = orderCollectionItems(items, effective, checkActive)
        return BrowseResult(ordered, scope(route))
    }
}

internal fun BrowserItem.duration(): Long? = facts.durationMs
/** Every collection kind, playlists included, sums its current distinct songs (#666). */
internal fun BrowserItem.longestListenedMs(): Long = facts.listenedMs
internal fun BrowserItem.metadataText(sort: TrackSort): String? = songs.mapNotNullTo(sortedSetOf()) {
    when (sort) {
        TrackSort.Artist -> it.track.artist; TrackSort.AlbumArtist -> it.track.albumArtist ?: it.track.artist
        TrackSort.Composer -> it.track.composer; TrackSort.Genre -> it.track.genre; else -> null
    }
}.takeIf { it.isNotEmpty() }?.joinToString(", ")

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
        ExtraInfo.Duration -> duration()?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}${if (facts.unknownDuration) " (partial)" else ""}" }
        ExtraInfo.Year -> facts.year?.toString()
        ExtraInfo.MostPlayed -> playInformation("$playCount ${if (playCount == 1L) "play" else "plays"}")
        ExtraInfo.LongestListened -> playInformation(listeningTimeLabel(longestListenedMs()) +
            if (facts.unknownListened) " (partial)" else "")
        ExtraInfo.Added -> addedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Modified -> modifiedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        ExtraInfo.Artist, ExtraInfo.AlbumArtist, ExtraInfo.Composer, ExtraInfo.Genre -> metadataText(TrackSort.valueOf(info.name))
        ExtraInfo.Automatic, ExtraInfo.Inherited -> subtitle
    }
}

private fun playInformation(text: String): String {
    io.github.anastaci1a.localica.diagnostics.WorkCounters.playInformationRows.incrementAndGet()
    return text
}

internal fun songCountLabel(count: Int) ="$count ${if (count == 1) "song" else "songs"}"

/** Listening totals grow past an hour, so hours join the shared m:ss clock style. */
internal fun listeningTimeLabel(ms: Long): String {
    val seconds = (ms / 1000 % 60).toString().padStart(2, '0')
    val minutes = ms / 60000 % 60
    val hours = ms / 3_600_000
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
