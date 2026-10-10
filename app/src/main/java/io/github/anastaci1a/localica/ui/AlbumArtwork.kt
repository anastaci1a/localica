package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkReader

@Composable
internal fun AlbumArtwork(uri: String?, modifier: Modifier = Modifier.size(200.dp)) {
    val context = LocalContext.current.applicationContext
    val reader = remember(context) { ArtworkReader(context) }
    val loaded by produceState<Pair<String?, ImageBitmap?>>(null to null, uri) {
        value = uri to null
        value = uri to uri?.let { reader.read(it)?.asImageBitmap() }
    }
    // produceState keeps its prior value until the new effect runs; never show stale artwork.
    val artwork = loaded.second.takeIf { loaded.first == uri }
    if (artwork != null) {
        Image(artwork, stringResource(R.string.album_artwork), modifier, contentScale = ContentScale.Crop)
    } else {
        Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
