package io.github.anastaci1a.localica.navigation

import java.util.UUID

enum class MainTab(val label: String) {
    Home("Home"), Library("Library"), Songs("Songs"), Albums("Albums"), Artists("Artists"),
    AlbumArtists("Album artists"), Folders("Folders"), Playlists("Playlists"), Queues("Queues"), MostPlayed("Most played")
}
enum class MediaKind(val label: String) {
    Song("Songs"), Album("Albums"), Artist("Artists"), AlbumArtist("Album artists"), Folder("Folders"), Playlist("Playlists"), Queue("Queues")
}
data class MediaRef(val kind: MediaKind, val id: String)
data class BrowseRoute(
    val tab: MainTab,
    val collection: MediaRef? = null,
    val title: String? = null,
    val search: Boolean = false,
    val globalSearch: Boolean = false,
    val text: String = "",
    val filter: MediaKind? = null,
    val key: String = UUID.randomUUID().toString(),
)
data class NavigationState(
    val active: MainTab = MainTab.Home,
    val stacks: Map<MainTab, List<BrowseRoute>> = mapOf(MainTab.Home to listOf(BrowseRoute(MainTab.Home))),
    val history: List<MainTab> = emptyList(),
) {
    val route: BrowseRoute get() = stacks.getValue(active).last()
    fun switch(tab: MainTab): NavigationState = if (tab == active) this else copy(active = tab,
        stacks = if (tab in stacks) stacks else stacks + (tab to listOf(BrowseRoute(tab))), history = history + active)
    fun open(route: BrowseRoute) = copy(stacks = stacks + (active to (stacks.getValue(active) + route)))
    fun update(route: BrowseRoute) = copy(stacks = stacks + (active to (stacks.getValue(active).dropLast(1) + route)))
    fun back(tabHistory: Boolean): NavigationState? {
        val stack = stacks.getValue(active)
        if (stack.size > 1) return copy(stacks = stacks + (active to stack.dropLast(1)))
        return if (tabHistory && history.isNotEmpty()) copy(active = history.last(), history = history.dropLast(1)) else null
    }
    fun reconcile(tabs: List<MainTab>): NavigationState {
        require(tabs.isNotEmpty())
        val selected = active.takeIf { it in tabs } ?: tabs.first()
        val kept = stacks.filterKeys { it in tabs }.toMutableMap()
        kept.putIfAbsent(selected, listOf(BrowseRoute(selected)))
        return copy(active = selected, stacks = kept, history = history.filter { it in tabs })
    }
    companion object {
        fun fresh(tabs: List<MainTab>): NavigationState {
            val first = if (MainTab.Home in tabs) MainTab.Home else tabs.first()
            return NavigationState(first, mapOf(first to listOf(BrowseRoute(first))))
        }
    }
}
data class InterfaceSettings(
    val tabs: List<MainTab> = listOf(MainTab.Home, MainTab.Library),
    val tabHistory: Boolean = true,
    val confirmExit: Boolean = false,
    val expandOnPlay: Boolean = true,
    val albumArtistFirst: Boolean = true,
    val reducedMotion: Boolean = false,
)
