package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
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

@Composable
internal fun MediaArtwork(uris: List<String>, modifier: Modifier, provider: CoverProvider = EmbeddedCoverProvider,
    shape: androidx.compose.ui.graphics.Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    var pixels by remember { mutableIntStateOf(0) }
    val request = Triple(uris, pixels, revision)
    val cached = remember(request, provider) {
        if (provider === EmbeddedCoverProvider) ArtworkCache.peek(uris, pixels) else null
    }
    val loaded by produceState<Pair<Triple<List<String>, Int, Long>?, ImageBitmap?>>(null to null, request, provider) {
        if (pixels > 0) value = request to provider.load(context, uris, pixels)?.asImageBitmap()
    }
    val sameContent = loaded.first?.first == uris && loaded.first?.third == revision
    val artwork = (if (sameContent) loaded.second else cached?.bitmap?.asImageBitmap())
    val measured = modifier.clip(shape).onSizeChanged { pixels = ArtworkCache.bucket(maxOf(it.width, it.height)) }
    val loading = cached == null && uris.isNotEmpty() && artwork == null && (pixels == 0 || loaded.first != request)
    ArtworkImage(artwork, loading, measured, shape, contentScale)
}

internal fun artworkShape(kind: io.github.anastaci1a.localica.navigation.MediaKind): androidx.compose.ui.graphics.Shape =
    when (kind) {
        io.github.anastaci1a.localica.navigation.MediaKind.Artist,
        io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist -> CircleShape
        else -> Corners.artwork
    }
