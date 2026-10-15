package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

enum class Presentation(val label: String) { List("List"), Compact("Compact gallery"), Standard("Standard gallery"), Large("Large gallery") }
enum class ExtraInfo { Automatic, None, Count, Duration, Year, Modified, Added, MostPlayed, Artist, AlbumArtist, Composer, Genre }
enum class SongGrouping(val label: String) { None("None"), Artist("Artist"), AlbumArtist("Album Artist"), Composer("Composer") }
// Names are persisted; density order is explicit so existing values stay compatible.
enum class ListSize { Compact, Normal, Large, Small, Smallest;
    val trailingInformation get() = this == Compact || this == Small || this == Smallest
    companion object { val densityOrder = listOf(Smallest, Small, Compact, Normal, Large) }
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
    init { migrateCatalogScopes() }
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
    private fun listKey(route: BrowseRoute) = BrowsePreferenceKeys.list(route)
    fun overridden(route: BrowseRoute) = sortSnapshot().overridden(route)
    fun sortSnapshot() = BrowseSortSnapshot.fromValues(store.all, legacyStore.all)
    fun observeSorts(onChange: (BrowseSortSnapshot) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "sort" || key == "ascending" || key.endsWith(".sort") || key.endsWith(".ascending") || key.endsWith(".group"))
                onChange(sortSnapshot())
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
            store.enumValue<ExtraInfo>("$key.info") ?: ExtraInfo.Automatic,
            store.getBoolean("$key.quick-search", true),
            sorts.grouping(route),
            store.getInt("$key.columns", 0).takeIf { it in 1..5 },
            store.enumValue<ListSize>("$key.list-size") ?: ListSize.Normal)
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
