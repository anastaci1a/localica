package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue

/** Identity revisions keep large source equality/hash work off Main and separate text from result freshness. */
internal class BrowseSourceRevision(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
    val queues: List<ListeningQueue>, val filters: LibraryFilters) {
    override fun equals(other: Any?) = other is BrowseSourceRevision && library === other.library &&
        playlists === other.playlists && queues === other.queues && filters == other.filters
    override fun hashCode() = 31 * (31 * (31 * System.identityHashCode(library) + System.identityHashCode(playlists)) +
        System.identityHashCode(queues)) + filters.hashCode()
}

internal data class PresentedBrowse(val route: BrowseRoute, val style: BrowseStyle, val result: BrowseResult,
    val source: BrowseSourceRevision, val sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default)

/** Activity-local, bounded completed queries. Short cache operations are serialized; query evaluation stays outside this monitor. */
internal class BrowsePresentationCache {
    private data class Entry(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val queues: List<ListeningQueue>, val filters: LibraryFilters, val presentation: PresentedBrowse)
    private val entries = mutableListOf<Entry>()
    private data class HomeEntry(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val pins: List<MediaRef>, val sorts: BrowseSortSnapshot, val items: List<BrowserItem>)
    private var home: HomeEntry? = null
    @Synchronized
    fun home(library: LibrarySnapshot, playlists: List<ManualPlaylist>, pins: List<MediaRef>,
        sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default): List<BrowserItem>? =
        home?.takeIf { it.library === library && it.playlists === playlists && it.pins == pins && it.sorts.sameCollections(sorts) }?.items
    @Synchronized
    fun putHome(library: LibrarySnapshot, playlists: List<ManualPlaylist>, pins: List<MediaRef>, items: List<BrowserItem>,
        sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default) {
        home = HomeEntry(library, playlists, pins, sorts, items)
    }
    @Synchronized
    fun get(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, route: BrowseRoute, style: BrowseStyle,
        sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default): PresentedBrowse? {
        val index = entries.indexOfFirst { it.library === library && it.playlists === playlists && it.queues === queues &&
            it.filters == filters && it.presentation.route == route && it.presentation.style.sameQuery(style) && it.presentation.sorts.sameCollections(sorts) }
        return if (index < 0) null else entries.removeAt(index).also { entries.add(it) }.presentation.copy(style = style, sorts = sorts)
    }
    @Synchronized
    fun put(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, presentation: PresentedBrowse) {
        entries.removeAll { it.library !== library || !it.presentation.sorts.sameCollections(presentation.sorts) || (it.presentation.route == presentation.route && it.presentation.style.sameQuery(presentation.style)) }
        entries.add(Entry(library, playlists, queues, filters, presentation))
        while (entries.size > 12) entries.removeAt(0)
    }
}

private fun BrowseStyle.sameQuery(other: BrowseStyle) = sort == other.sort && ascending == other.ascending && songGrouping == other.songGrouping

internal val LocalBrowsePresentationCache = compositionLocalOf<BrowsePresentationCache?> { null }
internal val LocalBrowseSortSnapshot = compositionLocalOf<BrowseSortSnapshot?> { null }

/** Normally provided once by the activity; isolated screens can observe the same preferences. */
@Composable
internal fun rememberBrowseSortSnapshot(): BrowseSortSnapshot {
    LocalBrowseSortSnapshot.current?.let { return it }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { BrowsePreferences(context) }
    var sorts by remember(preferences) { mutableStateOf(preferences.sortSnapshot()) }
    DisposableEffect(preferences) {
        val stop = preferences.observeSorts { sorts = it }
        // Close the read/register gap when this page first mounts.
        sorts = preferences.sortSnapshot()
        onDispose(stop)
    }
    return sorts
}

/** Warm bounded tab/queue results off Main before a gesture needs their first frame.
 * Artwork readers remain demand-driven so prefetch cannot block a visible cover. */
@Composable
internal fun WarmBrowsePresentations(cache: BrowsePresentationCache, library: LibrarySnapshot,
    playlists: List<ManualPlaylist>, queues: List<ListeningQueue>, filters: LibraryFilters, routes: List<BrowseRoute>,
    sorts: BrowseSortSnapshot) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { BrowsePreferences(context) }
    val source = BrowseSourceRevision(library, playlists, queues, filters)
    LaunchedEffect(source, routes, sorts) {
        for (route in routes.distinct()) {
            val style = preferences.read(route)
            if (cache.get(library, playlists, queues, filters, route, style, sorts) != null) continue
            val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val context = currentCoroutineContext()
                BrowseQueries.evaluate(library, playlists, route, style.query(), queues, filters.allCollections, sorts) { context.ensureActive() }
            }
            cache.put(library, playlists, queues, filters, PresentedBrowse(route, style, result, source, sorts))
        }
        // Warm at most two distinct global orders, sharing the catalog's total row budget.
        // Synthetic routes must not evict real tab/Back entries from the presentation cache.
        val globalSearches = (routes.map { it.tab } + MainTab.Home).distinct().map {
            val route = BrowseRoute(it, search = true, globalSearch = true, key = "warm-search:$it")
            route to preferences.read(route)
        }.distinctBy { (_, style) -> style.sort to style.ascending }.take(2)
        for ((route, style) in globalSearches) withContext(kotlinx.coroutines.Dispatchers.Default) {
            val context = currentCoroutineContext()
            BrowseQueries.evaluate(library, playlists, route, style.query(), queues, filters.allCollections, sorts) { context.ensureActive() }
        }
    }
}

internal data class BrowsePresentationState(val current: PresentedBrowse?, val cached: Boolean,
    val reveal: Animatable<Float, AnimationVector1D>, val pending: Boolean)

/** Publish matching query/style pairs atomically; filter motion never blocks query work. */
@Composable
internal fun rememberBrowsePresentation(library: LibrarySnapshot, playlists: List<ManualPlaylist>,
    queues: List<ListeningQueue>, libraryFilters: LibraryFilters, route: BrowseRoute, style: BrowseStyle,
    reducedMotion: Boolean, queryDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    sorts: BrowseSortSnapshot): BrowsePresentationState {
    // Query work and filter motion are independent. Departing rows begin fading
    // immediately; only a result for the latest request may replace them.
    val presentationCache = LocalBrowsePresentationCache.current ?: remember { BrowsePresentationCache() }
    val source = BrowseSourceRevision(library, playlists, queues, libraryFilters)
    val cachedPresentation = remember(source, route, style, sorts) {
        presentationCache.get(library, playlists, queues, libraryFilters, route, style, sorts)
    }
    val queriedPresentation by produceState(cachedPresentation, source, route, style, sorts) {
        if (cachedPresentation != null) { value = cachedPresentation; return@produceState }
        val queried = withContext(queryDispatcher) {
            val context = currentCoroutineContext()
            BrowseQueries.evaluate(library, playlists, route, style.query(), queues, libraryFilters.allCollections, sorts) { context.ensureActive() }
        }
        value = PresentedBrowse(route, style, queried, source, sorts).also {
            presentationCache.put(library, playlists, queues, libraryFilters, it)
        }
    }
    val candidate = cachedPresentation ?: queriedPresentation
    var presentation by remember(route.key) { mutableStateOf(cachedPresentation) }
    var displayedFilter by remember(route.key) { mutableStateOf(route.filter) }
    val reveal = remember(route.key) { androidx.compose.animation.core.Animatable(if (cachedPresentation == null) 0f else 1f) }
    LaunchedEffect(source, route, style, candidate, reducedMotion, sorts) {
        if (displayedFilter != route.filter) {
            if (reducedMotion) reveal.snapTo(0f) else reveal.animateTo(0f, androidx.compose.animation.core.tween(110))
        }
        if (candidate?.source == source && candidate.route == route && candidate.style == style && candidate.sorts == sorts) {
            presentation = candidate
            displayedFilter = route.filter
            if (reducedMotion) reveal.snapTo(1f) else if (reveal.value < 1f)
                reveal.animateTo(1f, androidx.compose.animation.core.tween(180))
        }
    }
    return BrowsePresentationState(presentation, cachedPresentation != null, reveal,
        presentation?.source != source || presentation?.route != route || presentation?.style != style || presentation?.sorts != sorts)
}
