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

/** Keep an opaque outgoing frame. Interruptions flatten the visible composite once;
 * the blur uses a render effect or prepared textures, never a per-frame bitmap filter. */
@Composable
internal fun ArtworkBlend(visual: PlayerVisual, modifier: Modifier, duration: Int, immediate: Boolean = false, opacityBlend: Boolean = false, occurrence: Any? = null,
    content: @Composable (PlayerVisual) -> Unit) {
    val target = visual.copy(loading = false)
    var acceptedOccurrence by remember { mutableStateOf(occurrence) }
    var outgoing by remember { mutableStateOf(target) }
    var incoming by remember { mutableStateOf(target) }
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    var frozenSoft by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(1f) }
    val layer = rememberGraphicsLayer()
    val readback = remember(layer) { RetainedLayerReadback() }
    val graphicsContext = LocalGraphicsContext.current
    val currentDensity by rememberUpdatedState(LocalDensity.current)
    val currentDirection by rememberUpdatedState(LocalLayoutDirection.current)
    val reduced = LocalReducedMotion.current
    val blurThrough = !reduced && !opacityBlend && LocalArtworkTransition.current == ArtworkTransition.BlurThrough
    LaunchedEffect(target, visual.loading, occurrence, immediate, graphicsContext) {
        // Loading carries the outgoing pixels, not a newly accepted visit.
        if (visual.loading && !immediate) return@LaunchedEffect
        if (immediate || occurrence == null && target.contentKey != null && target.contentKey == incoming.contentKey) {
            acceptedOccurrence = occurrence
            frozen = null; frozenSoft = null; outgoing = target; incoming = target
            progress.snapTo(1f)
        } else if (target != incoming || occurrence != acceptedOccurrence) {
            if (progress.value < 1f && layer.size.width > 0 && !layer.isReleased) {
                val image = readback.capture(layer, graphicsContext, currentDensity, currentDirection)
                val soft = if (Build.VERSION.SDK_INT < 31 && blurThrough) {
                    withContext(Dispatchers.Default) { blurGlassSnapshot(image, minOf(image.width, image.height) * .045f) }
                } else null
                frozen = image; frozenSoft = soft
            } else { frozen = null; frozenSoft = null; outgoing = incoming }
            acceptedOccurrence = occurrence
            incoming = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (reduced) minOf(duration, 120) else duration, easing = LinearEasing))
            frozen = null; frozenSoft = null; outgoing = incoming
        }
    }
    val transitioning by remember(immediate) { derivedStateOf { !immediate && progress.value < 1f } }
    fun phase() = if (immediate) 1f else progress.value
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
            else content(frame)
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
        if (transitioning) Box(Modifier.matchParentSize().clearAndSetSemantics {}) { Frame(outgoing, frozen, frozenSoft) }
        Box(Modifier.matchParentSize().graphicsLayer { alpha = reveal() }) { Frame(if (immediate) target else incoming) }
    }
}
