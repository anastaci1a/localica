package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.anastaci1a.localica.navigation.ArtworkTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalArtworkTransition = staticCompositionLocalOf { ArtworkTransition.BlurThrough }

private data class ArtworkBlendState(val incoming: PlayerVisual, val occurrence: Any?,
    val outgoing: PlayerVisual = incoming, val frozen: ImageBitmap? = null, val frozenSoft: ImageBitmap? = null,
    val progress: Animatable<Float, AnimationVector1D> = Animatable(1f))

/** Keep an opaque outgoing frame. Interruptions flatten the visible composite once;
 * the blur uses a render effect or prepared textures, never a per-frame bitmap filter. */
@Composable
internal fun ArtworkBlend(visual: PlayerVisual, modifier: Modifier, duration: Int, immediate: Boolean = false, opacityBlend: Boolean = false, occurrence: Any? = null,
    content: @Composable (PlayerVisual) -> Unit) {
    // Cold frames must remain gradient-only, including as the outgoing reveal frame.
    val target = visual.copy(loading = visual.loading && visual.artwork == null)
    var blend by remember { mutableStateOf(ArtworkBlendState(target, occurrence)) }
    val layer = rememberGraphicsLayer()
    val readback = remember(layer) { RetainedLayerReadback() }
    val graphicsContext = LocalGraphicsContext.current
    val currentDensity by rememberUpdatedState(LocalDensity.current)
    val currentDirection by rememberUpdatedState(LocalLayoutDirection.current)
    val reduced = LocalReducedMotion.current
    val blurThrough = !reduced && !opacityBlend && LocalArtworkTransition.current == ArtworkTransition.BlurThrough
    LaunchedEffect(target, visual.loading, occurrence, immediate, graphicsContext) {
        val previous = blend
        // Loading carries the outgoing pixels, not a newly accepted visit.
        if (visual.loading && !immediate) {
            if (previous.incoming.artwork == null && previous.outgoing.artwork == null && previous.frozen == null) {
                blend = ArtworkBlendState(target, previous.occurrence)
            }
            return@LaunchedEffect
        }
        if (immediate || occurrence == null && target.contentKey != null && target.contentKey == previous.incoming.contentKey) {
            blend = ArtworkBlendState(target, occurrence)
        } else if (target != previous.incoming || occurrence != previous.occurrence) {
            var outgoing = previous.incoming
            var frozen: ImageBitmap? = null
            var frozenSoft: ImageBitmap? = null
            if (previous.progress.value < 1f && layer.size.width > 0 && !layer.isReleased) {
                val image = readback.capture(layer, graphicsContext, currentDensity, currentDirection)
                val soft = if (Build.VERSION.SDK_INT < 31 && blurThrough) {
                    withContext(Dispatchers.Default) { blurGlassSnapshot(image, minOf(image.width, image.height) * .045f) }
                } else null
                outgoing = previous.outgoing
                frozen = image; frozenSoft = soft
            }
            // Admit the image tree and its own clock together. Previously mounted
            // draw closures keep their old opacity until this outgoing tree is composed.
            val next = ArtworkBlendState(target, occurrence, outgoing, frozen, frozenSoft, Animatable(0f))
            blend = next
            next.progress.animateTo(1f, tween(if (reduced) minOf(duration, 120) else duration, easing = LinearEasing))
            if (blend === next) blend = next.copy(outgoing = next.incoming, frozen = null, frozenSoft = null)
        }
    }
    // Capture one immutable bundle in both composition and every draw/layer closure.
    val composed = blend
    val transitioning by remember(composed, immediate) { derivedStateOf { !immediate && composed.progress.value < 1f } }
    fun phase() = if (immediate) 1f else composed.progress.value
    fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun blur() = if (blurThrough) phase().let { smooth(2f * minOf(it, 1f - it)) } else 0f
    fun reveal() = if (blurThrough) smooth((phase() - .35f) / .3f) else smooth(phase())
    @Composable fun Frame(frame: PlayerVisual, image: ImageBitmap? = null, soft: ImageBitmap? = null) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val blur = blur()
            renderEffect = if (Build.VERSION.SDK_INT >= 31 && blur > .001f) {
                val radius = minOf(size.width, size.height) * .045f * blur
                BlurEffect(radius, radius, TileMode.Clamp)
            } else null
        }) {
            if (image != null) Canvas(Modifier.matchParentSize()) { drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            else content(if (visual.loading && frame.artwork == null) frame.copy(loading = true) else frame)
            if (Build.VERSION.SDK_INT < 31 && blurThrough && transitioning) Box(Modifier.matchParentSize().graphicsLayer { alpha = blur() }.clearAndSetSemantics {}) {
                if (soft != null) Canvas(Modifier.matchParentSize()) { drawImage(soft, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                else if (image == null && frame.softArtwork != null) content(frame.copy(artwork = frame.softArtwork))
            }
        }
    }
    Box(modifier.drawWithContent {
        // Only an interruptible crossfade needs a snapshot source. Settled artwork
        // draws directly, and animation phases invalidate layers rather than content.
        if (transitioning) {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        } else drawContent()
    }) {
        if (transitioning) Box(Modifier.matchParentSize().clearAndSetSemantics {}) { Frame(composed.outgoing, composed.frozen, composed.frozenSoft) }
        Box(Modifier.matchParentSize().graphicsLayer { alpha = reveal() }) { Frame(if (immediate) target else composed.incoming) }
    }
}
