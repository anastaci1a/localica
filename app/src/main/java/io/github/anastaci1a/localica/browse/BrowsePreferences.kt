package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

enum class Presentation(val label: String) { List("List"), Compact("Compact gallery"), Standard("Standard gallery"), Large("Large gallery") }
enum class ExtraInfo { Automatic, None, Count, Duration, Year, Modified, Artist, AlbumArtist, Composer, Genre }
enum class SongGrouping(val label: String) { None("None"), Artist("Artist"), AlbumArtist("Album artist"), Composer("Composer") }
data class BrowseStyle(val sort: TrackSort = TrackSort.Title, val ascending: Boolean = true,
    val presentation: Presentation = Presentation.List, val extraInfo: ExtraInfo = ExtraInfo.Automatic,
    val quickSearch: Boolean = true, val songGrouping: SongGrouping = SongGrouping.None) {
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
        return BrowseStyle(TrackSort.entries.firstOrNull { it.name == store.getString("$key.sort", null) } ?: default.sort,
            store.getBoolean("$key.ascending", default.ascending),
            Presentation.entries.firstOrNull { it.name == store.getString("$key.presentation", null) } ?: Presentation.List,
            ExtraInfo.entries.firstOrNull { it.name == store.getString("$key.info", null) } ?: ExtraInfo.Automatic,
            store.getBoolean("$key.quick-search", true),
            SongGrouping.entries.firstOrNull { it.name == store.getString("$key.group", if (route.collection?.kind == MediaKind.Album)
                store.getString("catalog.Album.group", null) else null) } ?: SongGrouping.None).let {
            if (route.collection?.kind == MediaKind.Album && it.songGrouping != SongGrouping.None) it.copy(presentation = Presentation.List) else it
        }
    }
    fun write(route: BrowseRoute, value: BrowseStyle, override: Boolean) {
        val key = if (override) requireNotNull(collectionKey(route)) else type(route)
        store.edit().putString("$key.sort", value.sort.name).putBoolean("$key.ascending", value.ascending)
            .putString("$key.presentation", value.presentation.name).putString("$key.info", value.extraInfo.name)
            .putBoolean("$key.quick-search", value.quickSearch).putString("$key.group", value.songGrouping.name).apply()
        if (!override) reset(route)
    }
    fun reset(route: BrowseRoute) {
        val key = collectionKey(route) ?: return
        store.edit().remove("$key.sort").remove("$key.ascending").remove("$key.presentation").remove("$key.info")
            .remove("$key.quick-search").remove("$key.group").apply()
    }
}
