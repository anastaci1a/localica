package io.github.anastaci1a.localica.navigation

import java.util.UUID

enum class MainTab(val label: String) {
    Home("Home"), Library("Library"), Songs("Songs"), Albums("Albums"), Artists("Artists"),
    AlbumArtists("Album Artists"), Folders("Folders"), Playlists("Playlists"), Queues("Queues"), MostPlayed("Most played")
}
enum class MediaKind(val label: String) {
    Song("Songs"), Album("Albums"), Artist("Artists"), AlbumArtist("Album Artists"), Folder("Folders"), Playlist("Playlists"), Queue("Queues")
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
    val queryCommitted: Boolean = false,
    val querySort: io.github.anastaci1a.localica.library.TrackSort? = null,
    val queryAscending: Boolean? = null,
)
data class NavigationState(
    val active: MainTab = MainTab.Home,
    val stacks: Map<MainTab, List<BrowseRoute>> = mapOf(MainTab.Home to listOf(BrowseRoute(MainTab.Home))),
    val history: List<MainTab> = emptyList(),
) {
    val route: BrowseRoute get() = stacks.getValue(active).last()
    fun switch(tab: MainTab): NavigationState = if (tab == active) this else copy(active = tab,
        stacks = if (tab in stacks) stacks else stacks + (tab to listOf(BrowseRoute(tab))), history = history + active)
    fun resetTab(tab: MainTab): NavigationState {
        val root = stacks[tab]?.firstOrNull() ?: BrowseRoute(tab)
        return copy(stacks = stacks + (tab to listOf(if (tab == MainTab.Library) root.copy(filter = null) else root)))
    }
    fun open(route: BrowseRoute) = copy(stacks = stacks + (active to (stacks.getValue(active) + route)))
    fun update(route: BrowseRoute) = copy(stacks = stacks + (active to (stacks.getValue(active).dropLast(1) + route)))
    fun editSearch(next: BrowseRoute): NavigationState {
        val draft = next.copy(search = true, queryCommitted = false)
        return if (route.search && !route.queryCommitted) update(draft.copy(key = route.key))
            else open(draft.copy(key = UUID.randomUUID().toString()))
    }
    fun commitSearch(): NavigationState = if (route.search) update(route.copy(queryCommitted = true)) else this
    fun back(tabHistory: Boolean): NavigationState? {
        val stack = stacks.getValue(active)
        if (stack.size > 1) return copy(stacks = stacks + (active to stack.dropLast(1)))
        return if (tabHistory && history.isNotEmpty()) copy(active = history.last(), history = history.dropLast(1)) else null
    }
    fun reconcile(tabs: List<MainTab>): NavigationState {
        require(tabs.isNotEmpty())
        val selected = active.takeIf { it in tabs } ?: tabs.first()
        val kept = stacks.filterKeys { it in tabs }.toMutableMap()
        tabs.forEach { kept.putIfAbsent(it, listOf(BrowseRoute(it))) }
        return copy(active = selected, stacks = kept, history = history.filter { it in tabs })
    }
    companion object {
        fun fresh(tabs: List<MainTab>): NavigationState {
            val first = if (MainTab.Home in tabs) MainTab.Home else tabs.first()
            return NavigationState(first, tabs.associateWith { listOf(BrowseRoute(it)) })
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
    val artworkTap: ArtworkAction = ArtworkAction.Lyrics,
    val artworkHold: ArtworkAction = ArtworkAction.Menu,
    val fasterMotion: Boolean = false,
    val artworkLayout: ArtworkLayout = ArtworkLayout.FullBackground,
    val artworkTransition: ArtworkTransition = ArtworkTransition.SoftWash,
    val glass: GlassAppearance = GlassAppearance(),
    val grayTone: Float = .4f,
)

fun Float.normalizedGrayTone() = if (isFinite()) coerceIn(0f, 1f) else .4f

enum class TabLensStyle(val label: String) { Adjustable("Adjustable glass"), Overlay("Clear overlay lens"), Classic("Classic gray pill") }

data class GlassAppearance(
    val blur: Float = .2f,
    val whiteTint: Float = .06f,
    val shadowStrength: Float = .12f,
    val shadowSpread: Float = .6f,
    val contrast: Float = .4f,
    val tabStyle: TabLensStyle = TabLensStyle.Adjustable,
    val tabBlur: Float = .3f,
    val tabWhiteTint: Float = .16f,
) {
    fun normalized() = GlassAppearance(blur.unit(.2f), whiteTint.unit(.06f),
        shadowStrength.unit(.12f), shadowSpread.unit(.6f), contrast.unit(.4f), tabStyle,
        tabBlur.unit(.3f), tabWhiteTint.unit(.16f))
    private fun Float.unit(fallback: Float) = if (isFinite()) coerceIn(0f, 1f) else fallback
}

enum class ArtworkAction(val label: String) {
    Lyrics("Toggle lyrics"), Menu("Show artwork menu"), View("View artwork"), Change("Change artwork"), None("Do nothing")
}

enum class ArtworkLayout(val label: String) { FullBackground("Full-background artwork"), Floating("Floating artwork with matte background") }

enum class ArtworkTransition(val label: String) { SoftWash("Soft Wash"), FlowingDissolve("Flowing Dissolve"), GentleRipple("Gentle Ripple") }
