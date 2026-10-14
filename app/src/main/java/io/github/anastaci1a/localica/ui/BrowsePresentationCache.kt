package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import kotlinx.coroutines.withContext
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue

internal data class PresentedBrowse(val route: BrowseRoute, val style: BrowseStyle, val result: BrowseResult)

/** Activity-local, bounded completed queries. Source identities avoid hashing a whole library on Main. */
internal class BrowsePresentationCache {
    private data class Entry(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val queues: List<ListeningQueue>, val filters: LibraryFilters, val presentation: PresentedBrowse)
    private val entries = mutableListOf<Entry>()
    private data class HomeEntry(val library: LibrarySnapshot, val playlists: List<ManualPlaylist>,
        val pins: List<MediaRef>, val items: List<BrowserItem>)
    private var home: HomeEntry? = null
    fun home(library: LibrarySnapshot, playlists: List<ManualPlaylist>, pins: List<MediaRef>): List<BrowserItem>? =
        home?.takeIf { it.library === library && it.playlists === playlists && it.pins == pins }?.items
    fun putHome(library: LibrarySnapshot, playlists: List<ManualPlaylist>, pins: List<MediaRef>, items: List<BrowserItem>) {
        home = HomeEntry(library, playlists, pins, items)
    }
    fun get(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, route: BrowseRoute, style: BrowseStyle): PresentedBrowse? {
        val index = entries.indexOfFirst { it.library === library && it.playlists === playlists && it.queues === queues &&
            it.filters == filters && it.presentation.route == route && it.presentation.style.sameQuery(style) }
        return if (index < 0) null else entries.removeAt(index).also { entries.add(it) }.presentation.copy(style = style)
    }
    fun put(library: LibrarySnapshot, playlists: List<ManualPlaylist>, queues: List<ListeningQueue>,
        filters: LibraryFilters, presentation: PresentedBrowse) {
        entries.removeAll { it.library !== library || (it.presentation.route == presentation.route && it.presentation.style.sameQuery(presentation.style)) }
        entries.add(Entry(library, playlists, queues, filters, presentation))
        while (entries.size > 12) entries.removeAt(0)
    }
}

private fun BrowseStyle.sameQuery(other: BrowseStyle) = sort == other.sort && ascending == other.ascending && songGrouping == other.songGrouping

internal val LocalBrowsePresentationCache = compositionLocalOf<BrowsePresentationCache?> { null }

/** Warm bounded tab/queue results off Main before a gesture needs their first frame.
 * Artwork readers remain demand-driven so prefetch cannot block a visible cover. */
@Composable
internal fun WarmBrowsePresentations(cache: BrowsePresentationCache, library: LibrarySnapshot,
    playlists: List<ManualPlaylist>, queues: List<ListeningQueue>, filters: LibraryFilters, routes: List<BrowseRoute>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { BrowsePreferences(context) }
    LaunchedEffect(library, playlists, queues, filters, routes) {
        for (route in routes.distinct()) {
            val style = preferences.read(route)
            if (cache.get(library, playlists, queues, filters, route, style) != null) continue
            val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
                BrowseQueries.evaluate(library, playlists, route, style.query(), queues, filters.allCollections)
            }
            cache.put(library, playlists, queues, filters, PresentedBrowse(route, style, result))
        }
    }
}

internal data class BrowsePresentationState(val current: PresentedBrowse?, val cached: Boolean,
    val reveal: Animatable<Float, AnimationVector1D>, val pending: Boolean)

/** Publish matching query/style pairs atomically; filter motion never blocks query work. */
@Composable
internal fun rememberBrowsePresentation(library: LibrarySnapshot, playlists: List<ManualPlaylist>,
    queues: List<ListeningQueue>, libraryFilters: LibraryFilters, route: BrowseRoute, style: BrowseStyle,
    reducedMotion: Boolean, queryDispatcher: kotlinx.coroutines.CoroutineDispatcher): BrowsePresentationState {
    // Query work and filter motion are independent. Departing rows begin fading
    // immediately; only a result for the latest request may replace them.
    val presentationCache = LocalBrowsePresentationCache.current ?: remember { BrowsePresentationCache() }
    val cachedPresentation = remember(library, playlists, route, style, queues, libraryFilters) {
        presentationCache.get(library, playlists, queues, libraryFilters, route, style)
    }
    val queriedPresentation by produceState(cachedPresentation, library, playlists, route, style, queues, libraryFilters) {
        if (cachedPresentation != null) { value = cachedPresentation; return@produceState }
        val queried = withContext(queryDispatcher) {
            BrowseQueries.evaluate(library, playlists, route, style.query(), queues, libraryFilters.allCollections).let {
                if (route.collection?.kind == MediaKind.Album) it.groupAlbumSongs(style.songGrouping) else it
            }
        }
        value = PresentedBrowse(route, style, queried).also {
            presentationCache.put(library, playlists, queues, libraryFilters, it)
        }
    }
    val candidate = cachedPresentation ?: queriedPresentation
    var presentation by remember(route.key) { mutableStateOf(cachedPresentation) }
    var displayedFilter by remember(route.key) { mutableStateOf(route.filter) }
    val reveal = remember(route.key) { androidx.compose.animation.core.Animatable(if (cachedPresentation == null) 0f else 1f) }
    LaunchedEffect(route, style, candidate, reducedMotion) {
        if (displayedFilter != route.filter) {
            if (reducedMotion) reveal.snapTo(0f) else reveal.animateTo(0f, androidx.compose.animation.core.tween(110))
        }
        if (candidate?.route == route && candidate.style == style) {
            presentation = candidate
            displayedFilter = route.filter
            if (reducedMotion) reveal.snapTo(1f) else if (reveal.value < 1f)
                reveal.animateTo(1f, androidx.compose.animation.core.tween(180))
        }
    }
    return BrowsePresentationState(presentation, cachedPresentation != null, reveal,
        presentation?.route != route || presentation?.style != style)
}
