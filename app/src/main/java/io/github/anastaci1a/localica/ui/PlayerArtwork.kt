package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider

internal data class PlayerVisual(val artwork: ImageBitmap? = null, val palette: PlayerPalette = NeutralPalette,
    val loading: Boolean = false, val softArtwork: ImageBitmap? = null, val contentKey: String? = null)

/** Player-only retention: reusable library rows must still clear artwork when their identity changes. */
@Composable
internal fun rememberPlayerVisual(uri: String?, provider: CoverProvider = EmbeddedCoverProvider): PlayerVisual {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    val request = Triple(uri, revision, provider)
    val cached = if (provider === EmbeddedCoverProvider) uri?.let { PlayerVisualCache.peek(it, revision) } else null
    val loaded = remember {
        mutableStateOf<Pair<Triple<String?, Long, CoverProvider>?, PlayerVisual>>(
            cached?.let { request to it } ?: (null to PlayerVisual()))
    }
    LaunchedEffect(request) {
        if (uri != null) {
            val prepared = PlayerVisualCache.read(context, uri, provider)
            currentCoroutineContext().ensureActive()
            if (revision == ArtworkCache.revision.value) loaded.value = request to prepared
        }
    }
    // A warm request is ready in this composition, not after its producer runs. Keep
    // that accepted visual too, so a subsequent cold request retains its full palette.
    SideEffect { if (cached != null && revision == ArtworkCache.revision.value) loaded.value = request to cached }
    val completed = loaded.value
    val retained = if (completed.first?.third === provider) completed.second else PlayerVisual()
    return cached ?: retained.copy(loading = uri != null && completed.first != request)
}

/** A collection starts with its tapped thumbnail; preparing its large wash never blocks that frame. */
@Composable
internal fun rememberCollectionVisual(uri: String?, provider: CoverProvider = EmbeddedCoverProvider): PlayerVisual {
    val context = LocalContext.current.applicationContext
    val retention = LocalBrowseArtwork.current
    val revision by ArtworkCache.revision.collectAsState()
    val request = uri to revision
    val initial = remember(request, provider, retention) {
        retention?.visual(uri) ?: uri?.let { PlayerVisualCache.peek(it) } ?: run {
            val thumbnail = uri?.let { retention?.preview(it)?.bitmap ?: ArtworkCache.peek(listOf(it), 768)?.bitmap }
            if (thumbnail == null) PlayerVisual() else PlayerVisual(thumbnail.asImageBitmap(), thumbnailPalette(thumbnail))
        }
    }
    val loaded by produceState<Pair<Pair<String?, Long>, PlayerVisual>?>(null, request, provider) {
        if (uri != null) {
            if (initial.artwork != null && initial.palette.wash == null) {
                value = request to initial.copy(palette = playerPalette(initial.artwork.asAndroidBitmap()))
            }
            val visual = retention?.visual(uri) ?: PlayerVisualCache.read(context, uri, provider)
            currentCoroutineContext().ensureActive()
            retention?.keepVisual(uri, visual)
            value = request to visual
        }
    }
    return loaded?.takeIf { it.first == request }?.second ?: initial
}

@Composable
internal fun PlayerArtwork(visual: PlayerVisual, modifier: Modifier, shape: Shape, contentScale: ContentScale, duration: Int) {
    ArtworkBlend(visual, modifier.clip(shape), duration) { frame ->
        PlayerArtworkFrame(frame, Modifier.fillMaxSize(), shape, contentScale)
    }
}

@Composable
internal fun PlayerArtworkFrame(visual: PlayerVisual, modifier: Modifier, shape: Shape, contentScale: ContentScale) {
    Box(modifier.clip(shape)) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawRect(visual.palette.middle) }
        if (visual.artwork != null) Image(visual.artwork, stringResource(R.string.album_artwork), Modifier.fillMaxSize(), contentScale = contentScale)
        else if (visual.loading) ArtworkPlaceholder(true, Modifier.matchParentSize(), showIcon = false)
        else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_music), "No artwork",
            Modifier.align(androidx.compose.ui.Alignment.Center).fillMaxSize(.3f), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .4f))
    }
}

/** Decoded pixels remain shared with ArtworkCache; only a few prepared visual bundles are retained. */
internal class PreparedPlayerVisualCache(private val provider: CoverProvider, budgetBytes: Int = 40 * 1024 * 1024) {
    private data class Key(val uri: String, val revision: Long)
    // Conservative byte accounting includes shared images per entry. Eviction never recycles
    // bitmaps: the current frame, outgoing transition and library may still own references.
    private val cache = object : android.util.LruCache<Key, PlayerVisual>(budgetBytes) {
        override fun sizeOf(key: Key, value: PlayerVisual): Int =
            listOfNotNull(value.artwork?.asAndroidBitmap(), value.softArtwork?.asAndroidBitmap(),
                value.palette.wash, value.palette.softWash, value.palette.collectionWash)
                .distinct().sumOf { it.allocationByteCount } + key.uri.length * 2 + 128
    }
    private var cacheRevision = ArtworkCache.revision.value
    // Called under the cache monitor; retiring ownership never recycles shared pixels.
    private fun currentRevision(): Long = ArtworkCache.revision.value.also { revision ->
        if (cacheRevision != revision) { cache.evictAll(); cacheRevision = revision }
    }
    val retainedBytes: Int get() = synchronized(cache) { currentRevision(); cache.size() }
    private val preparation = Mutex()
    fun peek(uri: String, revision: Long = ArtworkCache.revision.value): PlayerVisual? = synchronized(cache) {
        if (currentRevision() == revision) cache.get(Key(uri, revision)) else null
    }
    suspend fun read(context: android.content.Context, uri: String): PlayerVisual {
        currentCoroutineContext().ensureActive()
        // Unrelated cold preparation must not delay a complete memory-cache hit.
        peek(uri)?.let { return it }
        return preparation.withLock {
            val key = synchronized(cache) { Key(uri, currentRevision()) }
            peek(uri, key.revision)?.let { return@withLock it }
            val bitmap = try { provider.load(context, listOf(uri), 768) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val result = if (bitmap == null) PlayerVisual() else {
                val image = bitmap.asImageBitmap()
                val fingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val row = IntArray(bitmap.width)
                    val bytes = java.nio.ByteBuffer.allocate(bitmap.width * 4)
                    for (y in 0 until bitmap.height) {
                        currentCoroutineContext().ensureActive()
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        bytes.clear(); row.forEach { bytes.putInt(it) }; digest.update(bytes.array())
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val candidates = synchronized(cache) {
                    if (currentRevision() == key.revision) cache.snapshot().values else emptyList()
                }
                val shared = candidates.firstOrNull {
                    it.contentKey == fingerprint && it.artwork?.width == bitmap.width && it.artwork.height == bitmap.height
                }
                shared ?: run {
                    val soft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        blurGlassSnapshot(image, bitmap.width * .035f)
                    }
                    PlayerVisual(image, playerPalette(bitmap), softArtwork = soft, contentKey = fingerprint)
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(cache) { if (key.revision == currentRevision()) cache.put(key, result) }
            result
        }
    }
}

internal object PlayerVisualCache {
    private val prepared = PreparedPlayerVisualCache(EmbeddedCoverProvider)
    fun peek(uri: String) = prepared.peek(uri)
    fun peek(uri: String, revision: Long) = prepared.peek(uri, revision)
    suspend fun read(context: android.content.Context, uri: String,
        provider: CoverProvider = EmbeddedCoverProvider): PlayerVisual =
        (if (provider === EmbeddedCoverProvider) prepared else PreparedPlayerVisualCache(provider)).read(context, uri)
}

@Composable
internal fun rememberNeighborArtwork(uri: String?): PlayerVisual? {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    val request = uri to revision
    val prepared by produceState<Pair<Pair<String?, Long>, PlayerVisual>?>(null, request) {
        value = if (uri == null) null else request to PlayerVisualCache.read(context, uri)
    }
    return prepared?.takeIf { it.first == request }?.second ?: uri?.let(PlayerVisualCache::peek)
}

/** Bounded, cancellable nearest-first preparation; transport ordering includes shuffle and repeats. */
@Composable
internal fun PreloadPlayerArtwork(uris: List<String>) {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    LaunchedEffect(uris, revision) {
        for (uri in uris.distinct().take(8)) {
            currentCoroutineContext().ensureActive()
            PlayerVisualCache.read(context, uri)
        }
    }
}
