package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

/** One immutable preference read for an entire query, including every collection's contents.
 * Layout changes do not change this value or invalidate completed artwork/query results. */
class BrowseSortSnapshot private constructor(
    private val values: Map<String, Any>, private val legacy: Map<String, Any>,
) {
    private val collectionKinds = listOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Folder)
    private val collectionOverrides = values.filterKeys { key -> collectionKinds.any { key.startsWith("contents.$it.$it.") } }
    private val inheritedOrders by lazy { collectionKinds.map { kind ->
        val route = collectionRoute(MediaRef(kind, ""))
        query(route, inherited = true) to grouping(route, inherited = true)
    } }
    private val catalogGrouping = SongGrouping.entries.firstOrNull { it.name == values["catalog.Album.group"] } ?: SongGrouping.None
    /** Catalog/search ordering has its own query key. Explicit defaults equal inherited defaults,
     * so saving the first layout preference need not recompute every collection's cover. */
    fun sameCollections(other: BrowseSortSnapshot) = inheritedOrders == other.inheritedOrders &&
        collectionOverrides == other.collectionOverrides && catalogGrouping == other.catalogGrouping && legacy == other.legacy

    fun query(route: BrowseRoute): LibraryQuery = query(route, inherited = false)
    private fun query(route: BrowseRoute, inherited: Boolean): LibraryQuery {
        val key = if (!inherited && overridden(route)) BrowsePreferenceKeys.list(route) else BrowsePreferenceKeys.type(route)
        val view = BrowseQueries.scope(route)
        val legacyPrefix = if (view == SongListView.Songs) "" else "${view.name}."
        val compatible = !route.search && route.collection?.kind != MediaKind.Queue &&
            (route.collection != null || route.tab == MainTab.Songs || route.filter == MediaKind.Song)
        val default = if (compatible && legacy.containsKey("${legacyPrefix}sort")) LibraryQuery(
            sort = TrackSort.entries.firstOrNull { it.name == legacy["${legacyPrefix}sort"] } ?: LibraryViews.defaults(view).sort,
            ascending = legacy["${legacyPrefix}ascending"] as? Boolean ?: true,
        ) else BrowseQueries.defaultQuery(route)
        return LibraryQuery(sort = TrackSort.entries.firstOrNull { it.name == values["$key.sort"] } ?: default.sort,
            ascending = values["$key.ascending"] as? Boolean ?: default.ascending)
    }

    fun grouping(route: BrowseRoute): SongGrouping = grouping(route, inherited = false)
    private fun grouping(route: BrowseRoute, inherited: Boolean): SongGrouping {
        val key = if (!inherited && overridden(route)) BrowsePreferenceKeys.list(route) else BrowsePreferenceKeys.type(route)
        val name = values["$key.group"] ?: values["catalog.Album.group"].takeIf { route.collection?.kind == MediaKind.Album }
        return SongGrouping.entries.firstOrNull { it.name == name } ?: SongGrouping.None
    }

    fun rows(ref: MediaRef, rows: List<SongRow>): List<SongRow> {
        val ordered = LibraryQueries.rows(rows, collectionQuery(ref))
        val grouping = if (ref.kind == MediaKind.Album) grouping(collectionRoute(ref)) else SongGrouping.None
        return if (grouping == SongGrouping.None) ordered else ordered.groupBy { grouping.label(it) }
            .toSortedMap(compareBy<String> { it.lowercase(java.util.Locale.ROOT) }.thenBy { it }).values.flatten()
    }

    override fun equals(other: Any?): Boolean = other is BrowseSortSnapshot && values == other.values && legacy == other.legacy
    override fun hashCode(): Int = 31 * values.hashCode() + legacy.hashCode()

    fun overridden(route: BrowseRoute) = BrowsePreferenceKeys.allowsOverride(route) &&
        values.containsKey("${BrowsePreferenceKeys.list(route)}.sort")

    fun collectionQuery(ref: MediaRef): LibraryQuery = when (ref.kind) {
        // Applying a sort persists the occurrence order. A view preference must never reorder it again.
        MediaKind.Playlist, MediaKind.Queue -> LibraryQuery(sort = TrackSort.Manual)
        else -> query(collectionRoute(ref))
    }

    private fun collectionRoute(ref: MediaRef) = BrowseRoute(MainTab.Library, collection = ref, key = "collection-sort")

    companion object {
        val Default = fromValues(emptyMap<String, Any>())
        fun fromValues(values: Map<String, *>, legacy: Map<String, *> = emptyMap<String, Any>()): BrowseSortSnapshot {
            fun ordering(source: Map<String, *>) = source.entries.mapNotNull { (key, value) ->
                if ((key == "sort" || key == "ascending" || key.endsWith(".sort") || key.endsWith(".ascending") || key.endsWith(".group")) &&
                    (value is String || value is Boolean)) key to requireNotNull(value) else null
            }.toMap()
            return BrowseSortSnapshot(ordering(values), ordering(legacy))
        }
    }
}

/** Preference identities are shared by style reads, snapshots and override writes. */
internal object BrowsePreferenceKeys {
    fun type(route: BrowseRoute): String = when {
        route.search -> "search"
        route.collection != null -> "contents.${route.collection.kind}"
        else -> "catalog.${route.filter?.name ?: when (route.tab) {
            MainTab.Songs -> MediaKind.Song.name
            MainTab.Albums -> MediaKind.Album.name
            MainTab.Artists -> MediaKind.Artist.name
            MainTab.AlbumArtists -> MediaKind.AlbumArtist.name
            MainTab.Folders -> MediaKind.Folder.name
            MainTab.Playlists -> MediaKind.Playlist.name
            else -> route.tab.name
        }}"
    }
    fun allowsOverride(route: BrowseRoute) = route.collection != null || route.search
    fun list(route: BrowseRoute): String = route.collection?.let { "${type(route)}.${it.kind}.${it.id.length}:${it.id}" }
        ?: "${type(route)}.tab.${route.tab}.${route.filter?.name ?: "All"}"
}

/** Missing artwork on the first member is a placeholder, never a different member's cover. */
fun collectionCoverUris(custom: String?, firstMember: String?): List<String> = listOfNotNull(custom ?: firstMember)
