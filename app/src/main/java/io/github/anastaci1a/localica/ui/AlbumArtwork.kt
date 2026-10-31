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
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider
import androidx.compose.ui.layout.onSizeChanged

@Composable
internal fun AlbumArtwork(uri: String?, modifier: Modifier = Modifier.size(200.dp),
    shape: androidx.compose.ui.graphics.Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop) {
    MediaArtwork(listOfNotNull(uri), modifier, shape = shape, contentScale = contentScale)
}

/** [sizeHint] is the expected longest edge in pixels; with it the first composition can peek the cache and start
 * a load without waiting for the first measurement. Measurement still wins once it differs. */
@Composable
internal fun MediaArtwork(uris: List<String>, modifier: Modifier, provider: CoverProvider = EmbeddedCoverProvider,
    shape: androidx.compose.ui.graphics.Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop,
    sizeHint: Int = 0) {
    val context = LocalContext.current.applicationContext
    ArtworkCache.configure(context)
    val revision by ArtworkCache.revision.collectAsState()
    val retention = LocalBrowseArtwork.current.takeIf { provider === EmbeddedCoverProvider }
    val deferred = LocalArtworkTransit.current?.active == true
    var pixels by remember { mutableIntStateOf(if (sizeHint > 0) ArtworkCache.bucket(sizeHint) else 0) }
    val request = Triple(uris, pixels, revision)
    val cached = remember(request, provider, retention) {
        retention?.peek(uris) ?: if (provider === EmbeddedCoverProvider) ArtworkCache.peek(uris, pixels) else null
    }
    val loaded by produceState<Pair<Triple<List<String>, Int, Long>?, ArtworkCache.CachedArtwork?>>(null to null, request, provider, deferred) {
        if (pixels > 0 && !deferred) value = request to (cached?.takeIf { it.edge >= pixels || it.bitmap == null }
            ?: ArtworkCache.CachedArtwork(provider.load(context, uris, pixels), pixels))
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
    val loading = !deferred && cached == null && uris.isNotEmpty() && artwork == null && (pixels == 0 || loaded.first != request)
    ArtworkImage(artwork, loading, measured, shape, contentScale)
}

internal fun artworkShape(kind: io.github.anastaci1a.localica.navigation.MediaKind,
    rounded: androidx.compose.ui.graphics.Shape = Corners.artwork): androidx.compose.ui.graphics.Shape =
    when (kind) {
        io.github.anastaci1a.localica.navigation.MediaKind.Artist,
        io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist -> CircleShape
        else -> rounded
    }
