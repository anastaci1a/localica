package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import io.github.anastaci1a.localica.ui.components.rememberArtworkReveal
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.anastaci1a.localica.R

/** Loading, absent artwork and image reveal are shared by every artwork surface. */
@Composable
internal fun ArtworkImage(artwork: ImageBitmap?, loading: Boolean, modifier: Modifier,
    shape: Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop,
    emptyText: String? = null, tintUris: List<String>? = null, textReveal: ArtworkTextReveal? = null,
    expectsArtwork: Boolean = true) {
    val reducedMotion = LocalReducedMotion.current
    val currentTextReveal by rememberUpdatedState(textReveal)
    // The reveal is read only while drawing; the cell never recomposes per animation frame.
    val present = artwork != null
    val reveal = rememberArtworkReveal(present, reducedMotion) {
        currentTextReveal?.let { it.release(); it.releaseMenu() }
    }
    // Art that replaces a loading shimmer fades in over that shimmer until the reveal completes (#524).
    // Only known-missing art shows the still placeholder and its icon.
    val revealsOverLoading = remember { BooleanArray(1) }
    if (artwork == null) revealsOverLoading[0] = loading
    val placeholderLoading = if (artwork == null) loading else revealsOverLoading[0]
    // Text waits exactly while the shimmer shows or the art reveals over it (#526).
    textReveal?.let {
        it.reveal = reveal
        it.hold(placeholderLoading && expectsArtwork)
        it.holdMenu(placeholderLoading)
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        ArtworkPlaceholderHost(artwork, reveal, placeholderLoading, emptyText, tintUris, Modifier.matchParentSize())
        if (!loading && artwork == null && emptyText != null) Text(emptyText)
        if (artwork != null) ArtworkResolutionBlend(artwork,
            Modifier.matchParentSize().graphicsLayer { alpha = reveal.value }, contentScale, if (reducedMotion) 0 else 240)
    }
}

/** Reads the reveal in its own composition, so only this host recomposes when the placeholder is removed. */
@Composable
private fun ArtworkPlaceholderHost(artwork: ImageBitmap?, reveal: State<Float>, loading: Boolean,
    emptyText: String?, tintUris: List<String>?, modifier: Modifier) {
    val revealed by remember(artwork) { derivedStateOf { artwork != null && reveal.value >= 1f } }
    if (!revealed) {
        Box(modifier) {
            ArtworkPlaceholder(loading, Modifier.matchParentSize(), showIcon = emptyText == null || loading, tintUris = tintUris)
        }
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

/** [tintUris] opts an artwork placeholder into its average-colour tint (#525); text skeletons
 * and the pre-catalog viewport omit it and keep the neutral base. */
@Composable
internal fun ArtworkPlaceholder(loading: Boolean, modifier: Modifier = Modifier, showIcon: Boolean = true,
    tintUris: List<String>? = null) {
    val loadingOpacity = LocalLoadingMaterialOpacity.current.coerceIn(0f, 1f)
    // The loading material never reaches the final missing-art fallback (#705).
    val base = (if (loading) LocalLoadingMaterialColor.current else null) ?: MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val reducedMotion = LocalReducedMotion.current
    val sharedActivity = LocalLoadingShimmerActivity.current
    if (loading && !reducedMotion && sharedActivity != null) DisposableEffect(sharedActivity) {
        sharedActivity.acquire()
        onDispose { sharedActivity.release() }
    }
    // Only loading artwork placeholders observe the colour index; it republishes at most a few times a second.
    val stored = if (loading && tintUris != null) io.github.anastaci1a.localica.library.ArtworkColors.lookup(tintUris) else null
    if (loading && tintUris != null && stored == null) DisposableEffect(tintUris) {
        io.github.anastaci1a.localica.library.ArtworkColors.prioritize(tintUris)
        onDispose { io.github.anastaci1a.localica.library.ArtworkColors.withdraw(tintUris) }
    }
    val known = stored?.takeIf { it != io.github.anastaci1a.localica.library.ArtworkColors.MISSING }
    val tint = remember(known, dark) { known?.let { Color(placeholderTint(it, dark)) } }
    // A colour known on the first frame draws tinted at once; one that arrives later eases from gray
    // in the loading wave's distance order. The phase is read only while drawing.
    val tintPhase = remember { Animatable(if (tint != null) 1f else 0f) }
    // A coordinate reference, resolved only when a cascade starts (no per-frame position work).
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(tint != null, reducedMotion) {
        if (tint != null && tintPhase.value < 1f) {
            if (reducedMotion) tintPhase.snapTo(1f) else {
                val origin = placed[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                kotlinx.coroutines.delay(BrowseLoadingMotion.delayMillis(origin.x / density, origin.y / density).toLong())
                tintPhase.animateTo(1f, tween(ArtworkTintTuning.CascadeMillis, easing = FastOutSlowInEasing))
            }
        }
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
        // One gradient per size, translated by the sweep: identical pixels without a new Brush and
        // native shader per placeholder per frame (every skeleton cell runs this while loading).
        // A tinted cell adds one cached light wash; the colour itself is a solid fill.
        androidx.compose.foundation.layout.Spacer(Modifier.matchParentSize()
            .then(if (tintUris != null) Modifier.onPlaced { placed[0] = it } else Modifier)
            .drawWithCache {
            val sweep = Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(-size.width * .6f, 0f), end = Offset(size.width * .6f, size.height))
            val wash = if (tint == null) null else Brush.linearGradient(listOf(Color.Transparent, TintedHighlight, Color.Transparent),
                start = Offset(-size.width * .6f, 0f), end = Offset(size.width * .6f, size.height))
            onDrawBehind {
                val phase = if (tint == null) 0f else tintPhase.value
                drawRect(if (tint == null || phase <= 0f) base else lerp(base, tint, phase))
                progress?.let {
                    val x = size.width * it.value
                    translate(left = x) {
                        if (phase < 1f) drawRect(sweep, topLeft = Offset(-x, 0f), size = size, alpha = 1f - phase)
                        if (wash != null && phase > 0f) drawRect(wash, topLeft = Offset(-x, 0f), size = size, alpha = phase)
                    }
                }
            }
        })
        if (showIcon && !loading) Icon(appIconPainter(AppIcon.TabSongs), null, Modifier.fillMaxSize(.4f).testTag("artwork-missing"), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
    }
}

/** The light half of the colour-to-light sweep over a tinted base. */
private val TintedHighlight = Color.White.copy(alpha = .16f)
