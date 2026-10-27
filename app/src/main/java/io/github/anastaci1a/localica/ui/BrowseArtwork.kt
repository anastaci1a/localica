package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.library.ArtworkCache

/** References to visible covers, not another decoded copy or an entire catalog. */
internal class BrowseArtworkScope {
    private var revision = ArtworkCache.revision.value
    private val visible = mutableMapOf<Any, Pair<List<String>, ArtworkCache.CachedArtwork>>()
    private var retained = emptyList<Pair<List<String>, ArtworkCache.CachedArtwork>>()
    private var transition: MutableMap<List<String>, ArtworkCache.CachedArtwork>? = null
    var preview: ArtworkCache.CachedArtwork? = null
        private set
    private var active = false
    private var prepared: Pair<String, PlayerVisual>? = null
    var collection: io.github.anastaci1a.localica.browse.BrowserItem? = null
        private set

    private fun current() {
        if (revision != ArtworkCache.revision.value) {
            revision = ArtworkCache.revision.value
            visible.clear(); retained = emptyList(); transition = null; preview = null; prepared = null
        }
    }
    fun activate(value: Boolean) {
        current()
        if (active && !value) { retained = visible.values.toList(); visible.clear() }
        active = value
    }
    fun seed(artwork: ArtworkCache.CachedArtwork?, item: io.github.anastaci1a.localica.browse.BrowserItem? = null) {
        current(); preview = artwork; collection = item
    }
    fun peek(uris: List<String>): ArtworkCache.CachedArtwork? {
        current()
        return (visible.values.asSequence() + retained.asSequence() +
            transition.orEmpty().asSequence().map { it.key to it.value }).filter { it.first == uris }
            .maxByOrNull { it.second.edge }?.second
    }
    fun hold(token: Any, uris: List<String>, artwork: ArtworkCache.CachedArtwork) {
        current(); visible[token] = uris to artwork
        transition?.get(uris)?.let { previous ->
            if (artwork.edge >= previous.edge) transition?.set(uris, artwork)
        }
        // A recreated viewport replaces its frozen snapshot as its items mount.
        retained = retained.filterNot { it.first == uris }
    }
    /** Pin only the source viewport, not every row visited by a later scroll.
     * A frozen tile atlas cannot seed a recreated MediaArtwork after LRU eviction. */
    fun retainForTransition(): () -> Unit {
        current()
        val snapshot = visible.values.groupBy { it.first }.mapValues { (_, covers) ->
            covers.maxBy { it.second.edge }.second
        }.toMutableMap()
        transition = snapshot
        return { if (transition === snapshot) transition = null }
    }
    fun release(token: Any) {
        val cover = visible.remove(token)
        if (!active && cover != null) retained = retained.filterNot { it.first == cover.first } + cover
    }
    fun preview(uri: String? = null): ArtworkCache.CachedArtwork? {
        current()
        return preview.takeIf { uri == null || collection == null || collection?.coverUris == listOf(uri) }
    }
    fun visual(uri: String?): PlayerVisual? { current(); return prepared?.takeIf { it.first == uri }?.second }
    fun keepVisual(uri: String, visual: PlayerVisual) { current(); prepared = uri to visual }
}

/** Keep each tab's current viewport and the two actual Back destinations. */
internal class BrowseArtworkStore {
    private val scopes = mutableMapOf<String, BrowseArtworkScope>()
    fun keep(keys: Set<String>, active: String) {
        scopes.keys.retainAll(keys)
        keys.forEach { scopes.getOrPut(it) { BrowseArtworkScope() }.activate(it == active) }
    }
    fun scope(key: String) = scopes[key]
    fun seed(key: String, artwork: ArtworkCache.CachedArtwork?, item: io.github.anastaci1a.localica.browse.BrowserItem? = null) {
        scopes.getOrPut(key) { BrowseArtworkScope() }.seed(artwork, item)
    }
}

internal val LocalBrowseArtwork = compositionLocalOf<BrowseArtworkScope?> { null }

/** Long automatic scrolls visit transient rows, not new browsing destinations.
 * Keep cached covers, but defer uncached I/O until the scroll settles or is interrupted. */
@Stable internal class ArtworkTransit {
    private var owners by mutableIntStateOf(0)
    val active get() = owners > 0
    suspend fun <T> during(far: Boolean, action: suspend () -> T): T {
        if (!far) return action()
        owners++
        try { return action() } finally { owners-- }
    }
}
internal val LocalArtworkTransit = staticCompositionLocalOf<ArtworkTransit?> { null }

/** A completed empty result must clear an old seed as well as a changed first member. */
internal fun collectionHeroUri(result: io.github.anastaci1a.localica.browse.BrowseResult?,
    seed: io.github.anastaci1a.localica.browse.BrowserItem?): String? =
    if (result == null) seed?.coverUris?.firstOrNull() else result.rows.firstOrNull()?.track?.uri

/** Match the playlist card's canonical manual/automatic cover, never a displayed
 * sort/filter result. Refresh the first physical occurrence's metadata exactly as
 * PlaylistEdits.rows does, retaining its unavailable-track fallback.
 */
internal fun playlistHeroUri(playlist: io.github.anastaci1a.localica.playlist.ManualPlaylist?,
    snapshot: io.github.anastaci1a.localica.library.LibrarySnapshot): String? {
    playlist ?: return null
    playlist.coverUri?.let { return it }
    val first = playlist.entries.firstOrNull()?.track
    val current = first?.let { stored -> snapshot.tracks.firstOrNull { it.id == stored.id } ?: stored }
    return io.github.anastaci1a.localica.browse.collectionCoverUris(playlist.coverUri, current?.uri).firstOrNull()
}
