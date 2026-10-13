package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.library.ArtworkCache

/** References to visible covers, not another decoded copy or an entire catalog. */
internal class BrowseArtworkScope {
    private var revision = ArtworkCache.revision.value
    private val visible = mutableMapOf<Any, Pair<List<String>, ArtworkCache.CachedArtwork>>()
    private var retained = emptyList<Pair<List<String>, ArtworkCache.CachedArtwork>>()
    var preview: ArtworkCache.CachedArtwork? = null
        private set
    private var active = false
    private var prepared: Pair<String, PlayerVisual>? = null

    private fun current() {
        if (revision != ArtworkCache.revision.value) {
            revision = ArtworkCache.revision.value
            visible.clear(); retained = emptyList(); preview = null; prepared = null
        }
    }
    fun activate(value: Boolean) {
        current()
        if (active && !value) { retained = visible.values.toList(); visible.clear() }
        active = value
    }
    fun seed(artwork: ArtworkCache.CachedArtwork?) { current(); preview = artwork }
    fun peek(uris: List<String>): ArtworkCache.CachedArtwork? {
        current()
        return (visible.values.asSequence() + retained.asSequence()).filter { it.first == uris }
            .maxByOrNull { it.second.edge }?.second
    }
    fun hold(token: Any, uris: List<String>, artwork: ArtworkCache.CachedArtwork) {
        current(); visible[token] = uris to artwork
        // A recreated viewport replaces its frozen snapshot as its items mount.
        retained = retained.filterNot { it.first == uris }
    }
    fun release(token: Any) {
        val cover = visible.remove(token)
        if (!active && cover != null) retained = retained.filterNot { it.first == cover.first } + cover
    }
    fun preview(): ArtworkCache.CachedArtwork? { current(); return preview }
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
    fun seed(key: String, artwork: ArtworkCache.CachedArtwork?) {
        scopes.getOrPut(key) { BrowseArtworkScope() }.seed(artwork)
    }
}

internal val LocalBrowseArtwork = compositionLocalOf<BrowseArtworkScope?> { null }
