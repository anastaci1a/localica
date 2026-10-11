package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider

internal data class PlayerVisual(val artwork: ImageBitmap? = null, val palette: PlayerPalette = NeutralPalette, val loading: Boolean = false)

/** Player-only retention: reusable library rows must still clear artwork when their identity changes. */
@Composable
internal fun rememberPlayerVisual(uri: String?, provider: CoverProvider = EmbeddedCoverProvider): PlayerVisual {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    val request = uri to revision
    val loaded by produceState<Pair<Pair<String?, Long>?, PlayerVisual>>(null to PlayerVisual(), request, provider) {
        val bitmap = provider.load(context, listOfNotNull(uri), 768)
        // Do not publish a placeholder between requests. Cancellation discards obsolete skips.
        value = request to if (bitmap == null) PlayerVisual() else PlayerVisual(bitmap.asImageBitmap(), playerPalette(bitmap))
    }
    return loaded.second.copy(loading = uri != null && loaded.first != request)
}

@Composable
internal fun PlayerArtwork(visual: PlayerVisual, modifier: Modifier, shape: Shape, contentScale: ContentScale, duration: Int) {
    ArtworkBlend(visual, modifier.clip(shape), duration) { frame ->
        if (frame.artwork != null) Image(frame.artwork, stringResource(R.string.album_artwork), Modifier.fillMaxSize(), contentScale = contentScale)
        else ArtworkPlaceholder(visual.loading, Modifier.fillMaxSize())
    }
}
