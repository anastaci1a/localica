package io.github.anastaci1a.localica.navigation

import java.util.UUID

enum class MainTab(val label: String) {
    Home("Home"), Library("Library"), Songs("Songs"), Albums("Albums"), Artists("Artists"),
    AlbumArtists("Album Artists"), Folders("Folders"), Playlists("Playlists"), Queues("Queues"), MostPlayed("Most played");
    companion object { val available get() = entries.filterNot { it == MostPlayed } }
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
    fun resetTab(tab: MainTab, clearLibraryFilter: Boolean = true): NavigationState {
        val root = stacks[tab]?.firstOrNull() ?: BrowseRoute(tab)
        return copy(stacks = stacks + (tab to listOf(if (tab == MainTab.Library && clearLibraryFilter) root.copy(filter = null) else root)))
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
/** How a long Return to top / return-to-current crosses a distant stretch. Reduced motion overrides it. */
enum class LongScrollStyle(val label: String) { FlyThrough("Fly-through"), JumpGlide("Jump, then glide"), Fade("Fade") }

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
    val artworkTransition: ArtworkTransition = ArtworkTransition.BlurThrough,
    val artworkSwipe: Boolean = true,
    val glass: GlassAppearance = GlassAppearance(),
    val grayTone: Float = .4f,
    val lyricDelayMs: Int = 0,
    val longScroll: LongScrollStyle = LongScrollStyle.FlyThrough,
)

fun Float.normalizedGrayTone() = if (isFinite()) coerceIn(0f, 1f) else .4f

enum class TabLensStyle(val label: String) { Adjustable("Adjustable glass"), Overlay("Clear overlay lens"), Classic("Classic gray pill") }

enum class GlassRendererMode(val label: String) { Automatic("Automatic"), Modern("Modern"), Legacy("Legacy") }

data class GlassAppearance(
    val blur: Float = .2f,
    val whiteTint: Float = .06f,
    val shadowStrength: Float = .12f,
    val shadowSpread: Float = .6f,
    val contrast: Float = .25f,
    val tabStyle: TabLensStyle = TabLensStyle.Adjustable,
    val tabBlur: Float = .3f,
    val tabWhiteTint: Float = .16f,
    val shapeDamping: Float = 8f,
    val depth: Float = 1.35f,
    val contrastWeight: Float = 2f,
    val classicOpacity: Float = .1f,
    val controlExpansion: Float = 1.75f,
    val overlayDarkening: Float = 0f,
    val overlayForeground: Boolean = false,
    val renderer: GlassRendererMode = GlassRendererMode.Automatic,
    /** Modern glass re-blurs the moving page every frame; off (default) holds the last frame until scrolling stops. */
    val liveScrollGlass: Boolean = false,
) {
    fun normalized() = GlassAppearance(blur.unit(.2f), whiteTint.unit(.06f),
        shadowStrength.unit(.12f), shadowSpread.unit(.6f), contrast.unit(.25f).coerceAtMost(.5f), tabStyle,
        tabBlur.unit(.3f), tabWhiteTint.unit(.16f),
        if (shapeDamping.isFinite()) shapeDamping.coerceIn(2f, 40f) else 8f,
        if (depth.isFinite()) depth.coerceIn(.6f, 2f) else 1.35f,
        if (contrastWeight.isFinite()) contrastWeight.coerceIn(1f, 2f) else 2f,
        classicOpacity.unit(.1f),
        if (controlExpansion.isFinite()) controlExpansion.coerceIn(1f, 2f) else 1.75f,
        overlayDarkening.unit(0f).coerceAtMost(.5f), overlayForeground, renderer, liveScrollGlass)
    private fun Float.unit(fallback: Float) = if (isFinite()) coerceIn(0f, 1f) else fallback
}

enum class ArtworkAction(val label: String) {
    Lyrics("Toggle lyrics"), Menu("Show artwork menu"), View("View artwork"), Change("Change artwork"), None("Do nothing")
}

enum class ArtworkLayout(val label: String) { FullBackground("Full-background artwork"), Floating("Floating artwork with matte background") }

enum class ArtworkTransition(val label: String) { BlurThrough("Blur through"), Carousel("Carousel") }
