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
        tween(if (reducedMotion) 0 else 300, easing = FastOutSlowInEasing), label = "Artwork reveal")
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (artwork == null || reveal < 1f) {
            ArtworkPlaceholder(loading && artwork == null, Modifier.matchParentSize(), showIcon = emptyText == null || loading)
            if (!loading && artwork == null && emptyText != null) Text(emptyText)
        }
        if (artwork != null) ArtworkResolutionBlend(artwork,
            Modifier.matchParentSize().graphicsLayer { alpha = reveal }, contentScale, if (reducedMotion) 0 else 240)
    }
}

private data class ArtworkResolutionFrame(val image: ImageBitmap, val weight: Float)

/** Complementary source-over alphas expose the background mid-crossfade. Keep the
 * outgoing composite opaque and reveal the new resolution over it instead. On an
 * interruption, retain its weighted image mixture without a GPU readback. */
@Composable
private fun ArtworkResolutionBlend(artwork: ImageBitmap, modifier: Modifier,
    contentScale: ContentScale, duration: Int) {
    var incoming by remember { mutableStateOf(artwork) }
    var outgoing by remember { mutableStateOf(emptyList<ArtworkResolutionFrame>()) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(artwork) {
        if (artwork != incoming) {
            val phase = progress.value
            outgoing = if (phase >= 1f) listOf(ArtworkResolutionFrame(incoming, 1f)) else {
                // Reversing across a resolution boundary reuses the same bitmap;
                // merge it rather than growing a layer for every interruption.
                val weights = linkedMapOf<ImageBitmap, Float>()
                outgoing.forEach { frame -> weights[frame.image] =
                    (weights[frame.image] ?: 0f) + frame.weight * (1f - phase) }
                weights[incoming] = (weights[incoming] ?: 0f) + phase
                weights.filterValues { it > 0f }.map { ArtworkResolutionFrame(it.key, it.value) }
            }
            incoming = artwork
            progress.snapTo(0f)
            progress.animateTo(1f, tween(duration))
            outgoing = emptyList()
        }
    }
    Box(modifier) {
        var accumulated = 0f
        outgoing.forEach { frame ->
            accumulated += frame.weight
            // Convert mixture weights to source-over alpha. The lowest nonzero
            // layer is opaque; later layers preserve the exact interrupted blend.
            val alpha = if (accumulated > 0f) frame.weight / accumulated else 0f
            Image(frame.image, stringResource(R.string.album_artwork),
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = contentScale)
        }
        Image(incoming, stringResource(R.string.album_artwork),
            Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }, contentScale = contentScale)
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
