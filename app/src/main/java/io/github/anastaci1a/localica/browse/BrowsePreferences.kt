package io.github.anastaci1a.localica.browse

import android.content.Context
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*

enum class Presentation(val label: String) { List("List"), Compact("Compact gallery"), Standard("Standard gallery"), Large("Large gallery") }
data class BrowseStyle(val sort: TrackSort = TrackSort.Title, val ascending: Boolean = true,
    val presentation: Presentation = Presentation.List) {
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
            Presentation.entries.firstOrNull { it.name == store.getString("$key.presentation", null) } ?: Presentation.List)
    }
    fun write(route: BrowseRoute, value: BrowseStyle, override: Boolean) {
        val key = if (override) requireNotNull(collectionKey(route)) else type(route)
        store.edit().putString("$key.sort", value.sort.name).putBoolean("$key.ascending", value.ascending)
            .putString("$key.presentation", value.presentation.name).apply()
        if (!override) reset(route)
    }
    fun reset(route: BrowseRoute) {
        val key = collectionKey(route) ?: return
        store.edit().remove("$key.sort").remove("$key.ascending").remove("$key.presentation").apply()
    }
}
