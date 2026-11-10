package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay

@Composable
internal fun AlbumArtwork(uri: String?, modifier: Modifier = Modifier.size(200.dp),
    shape: androidx.compose.ui.graphics.Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop) {
    MediaArtwork(listOfNotNull(uri), modifier, shape = shape, contentScale = contentScale)
}

@Composable
internal fun MediaArtwork(uris: List<String>, modifier: Modifier, provider: CoverProvider = EmbeddedCoverProvider,
    shape: androidx.compose.ui.graphics.Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop,
    textReveal: ArtworkTextReveal? = null, replacementArtwork: androidx.compose.ui.graphics.ImageBitmap? = null,
    fallbackPending: Boolean = false) {
    val context = LocalContext.current.applicationContext
    ArtworkCache.configure(context)
    val revision by ArtworkCache.revision.collectAsState()
    val retention = LocalBrowseArtwork.current.takeIf { provider === EmbeddedCoverProvider }
    val deferred = LocalArtworkTransit.current?.active == true
    var pixels by remember { mutableIntStateOf(0) }
    val request = Triple(uris, pixels, revision)
    val cached = remember(request, provider, retention) {
        retention?.peek(uris) ?: if (provider === EmbeddedCoverProvider) ArtworkCache.peek(uris, pixels) else null
    }
    // Covers that waited out a far journey resume on staggered frames, never all in one.
    val resumeStagger = remember { BooleanArray(1) }
    if (deferred) resumeStagger[0] = true
    val loaded by produceState<Pair<Triple<List<String>, Int, Long>?, ArtworkCache.CachedArtwork?>>(null to null, request, provider, deferred, fallbackPending, replacementArtwork != null) {
        if (pixels > 0 && !deferred && !fallbackPending && replacementArtwork == null) {
            val warm = cached?.takeIf { it.edge >= pixels || it.bitmap == null }
            if (warm == null && resumeStagger[0]) {
                resumeStagger[0] = false
                delay(ArtworkResumeStagger.next())
            }
            // Gate waits, retriever, decode and upload preparation stay off Main; only this single state write returns to it.
            value = request to (warm ?: ArtworkCache.CachedArtwork(
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    provider.load(context, uris, pixels).also { bitmap ->
                        // A visible cover without a stored colour supplies it from this decode, on IO (#525).
                        if (provider === EmbeddedCoverProvider && uris.size == 1) ArtworkColors.offer(uris[0], bitmap)
                    }
                }, pixels))
        }
    }
    val sameContent = loaded.first?.first == uris && loaded.first?.third == revision
    val retained = if (sameContent) loaded.second else cached
    val token = remember { Any() }
    DisposableEffect(retention, retained, uris, revision) {
        if (retained != null) retention?.hold(token, uris, retained)
        onDispose { retention?.release(token) }
    }
    val artwork = remember(retained?.bitmap) { retained?.bitmap?.asImageBitmap() }
    val measured = modifier.clip(shape).onSizeChanged { pixels = ArtworkCache.bucket(maxOf(it.width, it.height)) }
    // A deferred (far-journey) cover is pending, not missing. Only a cached or completed
    // empty result is known-missing art with the still placeholder (#524).
    // A stored "no artwork" row is known-missing on the first frame as well (#525).
    val indexedMissing = provider === EmbeddedCoverProvider && ArtworkColors.peek(uris) == ArtworkColors.MISSING
    val loading = cached == null && uris.isNotEmpty() && artwork == null && !indexedMissing &&
        (deferred || pixels == 0 || loaded.first != request)
    ArtworkImage(replacementArtwork ?: artwork.takeUnless { fallbackPending }, replacementArtwork == null && (loading || fallbackPending),
        measured, shape, contentScale, tintUris = uris.takeUnless { fallbackPending }, textReveal = textReveal,
        expectsArtwork = fallbackPending || provider === EmbeddedCoverProvider && ArtworkColors.peek(uris).let { it != null && it != ArtworkColors.MISSING })
}

internal fun artworkShape(kind: io.github.anastaci1a.localica.navigation.MediaKind,
    rounded: androidx.compose.ui.graphics.Shape = Corners.artwork): androidx.compose.ui.graphics.Shape =
    when (kind) {
        io.github.anastaci1a.localica.navigation.MediaKind.Artist,
        io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist -> CircleShape
        else -> rounded
    }

/** Spreads load starts over a few frames when a far journey ends: [perFrame] starts share each [frameMs]
 * slot and no start waits more than [maxMs], so every cover still begins within ~100 ms. */
internal class ArtworkStagger(private val perFrame: Int = 4, private val frameMs: Long = 16, private val maxMs: Long = 96,
    private val now: () -> Long = android.os.SystemClock::uptimeMillis) {
    private var windowEnd = Long.MIN_VALUE
    private var slot = 0
    @Synchronized fun next(): Long {
        val time = now()
        if (time > windowEnd) slot = 0
        val wait = minOf(slot / perFrame * frameMs, maxMs)
        slot++
        windowEnd = time + maxMs
        return wait
    }
}
internal val ArtworkResumeStagger = ArtworkStagger()
