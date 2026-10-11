package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.anastaci1a.localica.R

/** Loading, absent artwork and image reveal are shared by every artwork surface. */
@Composable
internal fun ArtworkImage(artwork: ImageBitmap?, loading: Boolean, modifier: Modifier,
    shape: Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop,
    emptyText: String? = null) {
    val reducedMotion = LocalReducedMotion.current
    val reveal by animateFloatAsState(if (artwork == null) 0f else 1f,
        tween(if (reducedMotion) 0 else 160), label = "Artwork reveal")
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (artwork == null || reveal < 1f) {
            ArtworkPlaceholder(loading && artwork == null, Modifier.matchParentSize(), showIcon = emptyText == null || loading)
            if (!loading && artwork == null && emptyText != null) Text(emptyText)
        }
        artwork?.let { image -> Image(image, stringResource(R.string.album_artwork),
            Modifier.matchParentSize().graphicsLayer { alpha = reveal }, contentScale = contentScale) }
    }
}

@Composable
internal fun ArtworkPlaceholder(loading: Boolean, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    // The sweep is read during drawing; it doesn't recompose or remeasure scrolling rows.
    val progress: State<Float>? = if (loading && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "Artwork loading").animateFloat(-1f, 2f,
            infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Artwork shimmer")
    } else null
    Box(modifier.then(if (loading) Modifier.testTag("artwork-loading") else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(base)
            progress?.let {
                val x = size.width * it.value
                drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x - size.width * .6f, 0f), end = Offset(x + size.width * .6f, size.height)))
            }
        }
        if (showIcon) Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
    }
}
