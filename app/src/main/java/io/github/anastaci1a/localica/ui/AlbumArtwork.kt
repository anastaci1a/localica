package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider
import androidx.compose.ui.layout.onSizeChanged

@Composable
internal fun AlbumArtwork(uri: String?, modifier: Modifier = Modifier.size(200.dp)) {
    MediaArtwork(listOfNotNull(uri), modifier)
}

@Composable
internal fun MediaArtwork(uris: List<String>, modifier: Modifier, provider: CoverProvider = EmbeddedCoverProvider) {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    var pixels by remember { mutableIntStateOf(0) }
    val request = Triple(uris, pixels, revision)
    val loaded by produceState<Pair<Triple<List<String>, Int, Long>?, ImageBitmap?>>(null to null, request, provider) {
        if (value.first?.first != uris || value.first?.third != revision) value = request to null
        if (pixels > 0) value = request to provider.load(context, uris, pixels)?.asImageBitmap()
    }
    val artwork = loaded.second.takeIf { loaded.first?.first == uris && loaded.first?.third == revision }
    val measured = modifier.clip(Corners.artwork).onSizeChanged { pixels = ArtworkCache.bucket(maxOf(it.width, it.height)) }
    if (artwork != null) {
        Image(artwork, stringResource(R.string.album_artwork), measured, contentScale = ContentScale.Crop)
    } else {
        Surface(measured, shape = Corners.artwork, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
