package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

enum class Presentation(val label: String) { List("List"), Compact("Compact gallery"), Standard("Standard gallery"), Large("Large gallery") }
enum class ExtraInfo { Automatic, Inherited, None, Count, Duration, Year, Modified, Added, MostPlayed, Artist, AlbumArtist, Composer, Genre }
enum class SongGrouping(val label: String) { None("None"), Artist("Artist"), AlbumArtist("Album Artist"), Composer("Composer") }
// Names are persisted; density order is explicit so existing values stay compatible.
enum class ListSize { Compact, Normal, Large, Small, Smallest, Tracklist;
    val trailingInformation get() = this == Compact || this == Small || this == Smallest
    companion object { val densityOrder = listOf(Smallest, Small, Compact, Tracklist, Normal, Large) }
}
data class BrowseStyle(val sort: TrackSort = TrackSort.Title, val ascending: Boolean = true,
    val presentation: Presentation = Presentation.List, val extraInfo: ExtraInfo = ExtraInfo.Automatic,
    val quickSearch: Boolean = true, val songGrouping: SongGrouping = SongGrouping.None,
    val itemsPerRow: Int? = null, val listSize: ListSize = ListSize.Normal) {
    fun query() = LibraryQuery(sort = sort, ascending = ascending)
}

/** Text belongs to the navigation route; only presentation and ordering live in preferences. */
class BrowsePreferences(context: Context) {
    private val store = context.getSharedPreferences("browse-style", Context.MODE_PRIVATE)
    private val legacyStore = context.getSharedPreferences("library-query", Context.MODE_PRIVATE)
    fun type(route: BrowseRoute): String = BrowsePreferenceKeys.type(route)
    init { migrateCatalogScopes(); migrateAlbumTracklist() }
    // Collection identities remain independent; roots share their catalog type.
    fun allowsOverride(route: BrowseRoute) = BrowsePreferenceKeys.allowsOverride(route)
    private fun migrateCatalogScopes() {
        if (store.getBoolean("shared-catalog-scopes", false)) return
        val edit = store.edit()
        val all = store.all
        val dedicated = mapOf(MediaKind.Song to MainTab.Songs, MediaKind.Album to MainTab.Albums,
            MediaKind.Artist to MainTab.Artists, MediaKind.AlbumArtist to MainTab.AlbumArtists,
            MediaKind.Folder to MainTab.Folders, MediaKind.Playlist to MainTab.Playlists)
        fun move(from: String?, to: String) {
            if (from == null) return
            all.filterKeys { it.startsWith("$from.") }.forEach { (key, value) ->
                val target = to + key.removePrefix(from)
                when (value) {
                    is String -> edit.putString(target, value)
                    is Boolean -> edit.putBoolean(target, value)
                    is Int -> edit.putInt(target, value)
                }
            }
        }
        dedicated.forEach { (kind, tab) ->
            val type = "catalog.$kind"
            val library = "$type.tab.Library.$kind"
            val separate = "$type.tab.$tab.All"
            move(when {
                store.contains("$library.sort") -> library
                store.contains("$separate.sort") -> separate
                else -> null
            }, type)
        }
        listOf(MainTab.Home, MainTab.Library, MainTab.Queues).forEach { tab ->
            val type = "catalog.$tab"
            val old = "$type.tab.$tab.All"
            if (store.contains("$old.sort")) move(old, type)
        }
        all.keys.filter { it.startsWith("catalog.") && ".tab." in it }.forEach { edit.remove(it) }
        edit.putBoolean("shared-catalog-scopes", true).apply()
    }
    /** Old album Normal meant numbered rows, not ordinary Normal artwork. */
    private fun migrateAlbumTracklist() {
        if (store.getBoolean("album-tracklist", false)) return
        val edit = store.edit()
        store.all.forEach { (key, value) ->
            if (key.startsWith("contents.Album.") && key.endsWith(".list-size") && value == "Normal")
                edit.putString(key, ListSize.Tracklist.name)
        }
        edit.putBoolean("album-tracklist", true).apply()
    }
    private fun listKey(route: BrowseRoute) = BrowsePreferenceKeys.list(route)
    fun overridden(route: BrowseRoute) = sortSnapshot().overridden(route)
    fun sortSnapshot() = BrowseSortSnapshot.fromValues(store.all, legacyStore.all)
    fun observeSorts(onChange: (BrowseSortSnapshot) -> Unit): () -> Unit =
        observeChanges({ it.isOrderingPreference() }) { onChange(sortSnapshot()) }

    private fun String.isOrderingPreference() = this == "sort" || this == "ascending" ||
        endsWith(".sort") || endsWith(".ascending") || endsWith(".group")

    /** Both consumers observe current and legacy stores, but information-only changes never
     * invalidate sorted artwork or a source+sort query result. */
    private fun observeChanges(relevant: (String) -> Boolean, onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || relevant(key)) onChange()
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        legacyStore.registerOnSharedPreferenceChangeListener(listener)
        return {
            store.unregisterOnSharedPreferenceChangeListener(listener)
            legacyStore.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    fun read(route: BrowseRoute): BrowseStyle {
        val sorts = sortSnapshot()
        val key = if (sorts.overridden(route)) listKey(route) else type(route)
        val order = sorts.query(route)
        return BrowseStyle(order.sort, order.ascending,
            store.enumValue<Presentation>("$key.presentation") ?: Presentation.List,
            (store.enumValue<ExtraInfo>("$key.info") ?: if (route.allowsInheritedInformation()) ExtraInfo.Inherited else ExtraInfo.Automatic)
                .let { if (it == ExtraInfo.Inherited && !route.allowsInheritedInformation()) ExtraInfo.Automatic else it },
            store.getBoolean("$key.quick-search", true),
            sorts.grouping(route),
            store.getInt("$key.columns", 0).takeIf { it in 1..5 },
            store.enumValue<ListSize>("$key.list-size") ?: route.defaultListSize())
    }
    /** Reuse the artwork resolver and one immutable read for every row type. */
    fun informationStyles(): Map<MediaKind, BrowseStyle> {
        val values = store.all
        val sorts = BrowseSortSnapshot.fromValues(values, legacyStore.all)
        return MediaKind.entries.associateWith { kind ->
            val route = if (kind == MediaKind.Queue) BrowseRoute(MainTab.Queues)
                else BrowseRoute(MainTab.Library, filter = kind)
            val key = BrowsePreferenceKeys.type(route)
            val info = ExtraInfo.entries.firstOrNull { it.name == values["$key.info"] }
                ?.takeUnless { it == ExtraInfo.Inherited } ?: ExtraInfo.Automatic
            val order = sorts.query(route)
            BrowseStyle(sort = order.sort, ascending = order.ascending, extraInfo = info)
        }
    }
    fun observeInformation(onChange: (Map<MediaKind, BrowseStyle>) -> Unit): () -> Unit {
        val stop = observeChanges({ it.isOrderingPreference() || (it.startsWith("catalog.") && it.endsWith(".info")) }) {
            onChange(informationStyles())
        }
        // Close the gap between an initial read and listener registration.
        onChange(informationStyles())
        return stop
    }
    fun write(route: BrowseRoute, value: BrowseStyle, override: Boolean) {
        val key = if (override && allowsOverride(route)) listKey(route) else type(route)
        val edit = store.edit()
        if (!override) clearOverride(edit, route)
        edit.putString("$key.sort", value.sort.name).putBoolean("$key.ascending", value.ascending)
            .putString("$key.presentation", value.presentation.name).putString("$key.info", value.extraInfo.name)
            .putBoolean("$key.quick-search", value.quickSearch).putString("$key.group", value.songGrouping.name)
            .putInt("$key.columns", value.itemsPerRow?.coerceIn(1, 5) ?: 0)
            .putString("$key.list-size", value.listSize.name).apply()
    }
    fun observe(route: BrowseRoute, onChange: (BrowseStyle) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(type(route) + ".")) onChange(read(route))
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun reset(route: BrowseRoute) {
        store.edit().also { clearOverride(it, route) }.apply()
    }
    private fun clearOverride(edit: android.content.SharedPreferences.Editor, route: BrowseRoute) {
        val key = listKey(route)
        listOf("sort", "ascending", "presentation", "info", "quick-search", "group", "columns", "list-size")
            .forEach { edit.remove("$key.$it") }
    }
}

/** Inheritance belongs only to the mixed roots, never a single-type or scoped list. */
internal fun BrowseRoute.allowsInheritedInformation(): Boolean = collection == null && filter == null &&
    (if (search) globalSearch else tab == MainTab.Library)

internal fun BrowseRoute.informationChoices(): List<ExtraInfo> =
    listOf(ExtraInfo.None) + (if (allowsInheritedInformation()) listOf(ExtraInfo.Inherited) else emptyList()) +
        listOf(ExtraInfo.Automatic) + ExtraInfo.entries.filterNot {
            it in setOf(ExtraInfo.None, ExtraInfo.Inherited, ExtraInfo.Automatic)
        }

/** Numbered album-style rows are also available for ordered mutable song collections. */
internal fun BrowseRoute.supportsTracklist() = collection?.kind in setOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Queue)
internal fun BrowseRoute.listSizes() = ListSize.densityOrder.filter { it != ListSize.Tracklist || supportsTracklist() }
internal fun BrowseRoute.defaultListSize() = if (collection?.kind == MediaKind.Album && !search) ListSize.Tracklist else ListSize.Normal

/** Album dense rows retain their numbers; Normal is ordinary cover/subtitle presentation everywhere. */
internal fun BrowseStyle.trackNumberLabel(albumNumber: String?, index: Int): String? = when {
    presentation != Presentation.List -> null
    listSize == ListSize.Tracklist -> albumNumber ?: (index + 1).toString()
    listSize in setOf(ListSize.Smallest, ListSize.Small, ListSize.Compact) -> albumNumber
    else -> null
}
