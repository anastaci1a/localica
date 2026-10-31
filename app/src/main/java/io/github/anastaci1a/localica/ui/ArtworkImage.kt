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
    // The reveal is read only while drawing; the cell never recomposes per animation frame.
    val reveal = remember { Animatable(if (artwork == null) 0f else 1f) }
    val present = artwork != null
    LaunchedEffect(present, reducedMotion) {
        reveal.animateTo(if (present) 1f else 0f, tween(if (reducedMotion) 0 else 300, easing = FastOutSlowInEasing))
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        // Hidden at the draw step the instant the reveal completes. It stays composed (a zero-alpha layer is
        // never drawn) so completion needs no second recomposition of the cell.
        Box(Modifier.matchParentSize().graphicsLayer { if (artwork != null && reveal.value >= 1f) alpha = 0f }) {
            ArtworkPlaceholder(loading && artwork == null, Modifier.matchParentSize(), showIcon = emptyText == null || loading)
        }
        if (!loading && artwork == null && emptyText != null) Text(emptyText)
        if (artwork != null) ArtworkResolutionBlend(artwork,
            Modifier.matchParentSize().graphicsLayer { alpha = reveal.value }, contentScale, if (reducedMotion) 0 else 240)
    }
}

private data class ArtworkResolutionFrame(val image: ImageBitmap, val weight: Float)
private data class ArtworkResolutionBlendState(val incoming: ImageBitmap,
    val outgoing: List<ArtworkResolutionFrame> = emptyList(),
    val progress: Animatable<Float, AnimationVector1D> = Animatable(1f))

/** Complementary source-over alphas expose the background mid-crossfade. Keep the
 * outgoing composite opaque and reveal the new resolution over it instead. On an
 * interruption, retain its weighted image mixture without a GPU readback. */
@Composable
private fun ArtworkResolutionBlend(artwork: ImageBitmap, modifier: Modifier,
    contentScale: ContentScale, duration: Int) {
    var blend by remember { mutableStateOf(ArtworkResolutionBlendState(artwork)) }
    LaunchedEffect(artwork) {
        val previous = blend
        if (artwork != previous.incoming) {
            val phase = previous.progress.value
            val outgoing = if (phase >= 1f) listOf(ArtworkResolutionFrame(previous.incoming, 1f)) else {
                // Reversing across a resolution boundary reuses the same bitmap;
                // merge it rather than growing a layer for every interruption.
                val weights = linkedMapOf<ImageBitmap, Float>()
                previous.outgoing.forEach { frame -> weights[frame.image] =
                    (weights[frame.image] ?: 0f) + frame.weight * (1f - phase) }
                weights[previous.incoming] = (weights[previous.incoming] ?: 0f) + phase
                weights.filterValues { it > 0f }.map { ArtworkResolutionFrame(it.key, it.value) }
            }
            // Publish the new image tree and its animation as one state. The old
            // composed layer keeps its old alpha until outgoing content is mounted;
            // resetting a shared Animatable first would expose a blank frame.
            val next = ArtworkResolutionBlendState(artwork, outgoing, Animatable(0f))
            blend = next
            next.progress.animateTo(1f, tween(duration))
            blend = next.copy(outgoing = emptyList())
        }
    }
    val composed = blend
    Box(modifier) {
        var accumulated = 0f
        composed.outgoing.forEach { frame ->
            accumulated += frame.weight
            // Convert mixture weights to source-over alpha. The lowest nonzero
            // layer is opaque; later layers preserve the exact interrupted blend.
            val alpha = if (accumulated > 0f) frame.weight / accumulated else 0f
            Image(frame.image, stringResource(R.string.album_artwork),
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = contentScale)
        }
        Image(composed.incoming, stringResource(R.string.album_artwork),
            Modifier.fillMaxSize().graphicsLayer { alpha = composed.progress.value }, contentScale = contentScale)
    }
}

@Composable
internal fun ArtworkPlaceholder(loading: Boolean, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    val loadingOpacity = LocalLoadingMaterialOpacity.current.coerceIn(0f, 1f)
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    val reducedMotion = LocalReducedMotion.current
    val sharedActivity = LocalLoadingShimmerActivity.current
    if (loading && !reducedMotion && sharedActivity != null) DisposableEffect(sharedActivity) {
        sharedActivity.acquire()
        onDispose { sharedActivity.release() }
    }
    // The sweep is read during drawing; it doesn't recompose or remeasure scrolling rows.
    val progress: State<Float>? = if (loading && !reducedMotion) {
        if (sharedActivity != null) LocalLoadingShimmer.current
        else LocalLoadingShimmer.current ?: rememberInfiniteTransition(label = "Artwork loading").animateFloat(-1f, 2f,
            infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Artwork shimmer")
    } else null
    Box(modifier.then(if (loading) Modifier.testTag("artwork-loading") else Modifier)
        .then(if (loading && loadingOpacity != 1f) Modifier.graphicsLayer { alpha = loadingOpacity } else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(base)
            progress?.let {
                val x = size.width * it.value
                drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x - size.width * .6f, 0f), end = Offset(x + size.width * .6f, size.height)))
            }
        }
        if (showIcon && !loading) Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
    }
}
