package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

enum class Presentation(val label: String) { List("List"), Compact("Compact gallery"), Standard("Standard gallery"), Large("Large gallery") }
enum class ExtraInfo { Automatic, None, Count, Duration, Year, Modified, Added, MostPlayed, Artist, AlbumArtist, Composer, Genre }
enum class SongGrouping(val label: String) { None("None"), Artist("Artist"), AlbumArtist("Album Artist"), Composer("Composer") }
data class BrowseStyle(val sort: TrackSort = TrackSort.Title, val ascending: Boolean = true,
    val presentation: Presentation = Presentation.List, val extraInfo: ExtraInfo = ExtraInfo.Automatic,
    val quickSearch: Boolean = true, val songGrouping: SongGrouping = SongGrouping.None,
    val itemsPerRow: Int? = null) {
    fun query() = LibraryQuery(sort = sort, ascending = ascending)
}

/** Text belongs to the navigation route; only presentation and ordering live in preferences. */
class BrowsePreferences(context: Context) {
    private val store = context.getSharedPreferences("browse-style", Context.MODE_PRIVATE)
    private val legacyStore = context.getSharedPreferences("library-query", Context.MODE_PRIVATE)
    private val legacy = AndroidLibraryQueryPreferences(context)
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
    private fun collectionKey(route: BrowseRoute): String? = route.collection?.let { "${type(route)}.${it.kind}.${it.id.length}:${it.id}" }
    fun overridden(route: BrowseRoute) = collectionKey(route)?.let { store.contains("$it.sort") } == true
    fun read(route: BrowseRoute): BrowseStyle {
        val key = if (overridden(route)) collectionKey(route)!! else type(route)
        val view = BrowseQueries.scope(route)
        val legacyKey = if (view == SongListView.Songs) "sort" else "${view.name}.sort"
        val compatible = !route.search && route.collection?.kind != MediaKind.Queue &&
            (route.collection != null || route.tab == MainTab.Songs || route.filter == MediaKind.Song)
        val default = if (compatible && legacyStore.contains(legacyKey)) legacy.read(view) else BrowseQueries.defaultQuery(route)
        return BrowseStyle(store.enumValue<TrackSort>("$key.sort") ?: default.sort,
            store.getBoolean("$key.ascending", default.ascending),
            store.enumValue<Presentation>("$key.presentation") ?: Presentation.List,
            store.enumValue<ExtraInfo>("$key.info") ?: ExtraInfo.Automatic,
            store.getBoolean("$key.quick-search", true),
            store.enumValue<SongGrouping>("$key.group", if (route.collection?.kind == MediaKind.Album)
                store.getString("catalog.Album.group", null) else null) ?: SongGrouping.None,
            store.getInt("$key.columns", 0).takeIf { it in 1..5 }).let {
            if (!route.search && route.collection?.kind == MediaKind.Album) it.copy(sort = TrackSort.Album, ascending = true, presentation = Presentation.List, songGrouping = SongGrouping.None) else it
        }
    }
    fun write(route: BrowseRoute, value: BrowseStyle, override: Boolean) {
        val key = if (override) requireNotNull(collectionKey(route)) else type(route)
        store.edit().putString("$key.sort", value.sort.name).putBoolean("$key.ascending", value.ascending)
            .putString("$key.presentation", value.presentation.name).putString("$key.info", value.extraInfo.name)
            .putBoolean("$key.quick-search", value.quickSearch).putString("$key.group", value.songGrouping.name)
            .putInt("$key.columns", value.itemsPerRow?.coerceIn(1, 5) ?: 0).apply()
        if (!override) reset(route)
    }
    fun observe(route: BrowseRoute, onChange: (BrowseStyle) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(type(route) + ".")) onChange(read(route))
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun reset(route: BrowseRoute) {
        val key = collectionKey(route) ?: return
        store.edit().remove("$key.sort").remove("$key.ascending").remove("$key.presentation").remove("$key.info")
            .remove("$key.quick-search").remove("$key.group").remove("$key.columns").apply()
    }
}
