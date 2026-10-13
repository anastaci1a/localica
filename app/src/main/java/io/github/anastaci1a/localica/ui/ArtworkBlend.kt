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
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.anastaci1a.localica.navigation.ArtworkTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalArtworkTransition = staticCompositionLocalOf { ArtworkTransition.BlurThrough }

/** Keep an opaque outgoing frame. Interruptions flatten the visible composite once;
 * the blur uses a render effect or prepared textures, never a per-frame bitmap filter. */
@Composable
internal fun ArtworkBlend(visual: PlayerVisual, modifier: Modifier, duration: Int, immediate: Boolean = false, opacityBlend: Boolean = false,
    content: @Composable (PlayerVisual) -> Unit) {
    val target = visual.copy(loading = false)
    var outgoing by remember { mutableStateOf(target) }
    var incoming by remember { mutableStateOf(target) }
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    var frozenSoft by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(1f) }
    val layer = rememberGraphicsLayer()
    val reduced = LocalReducedMotion.current
    val blurThrough = !reduced && !opacityBlend && LocalArtworkTransition.current == ArtworkTransition.BlurThrough
    LaunchedEffect(target, immediate) {
        if (immediate || target.contentKey != null && target.contentKey == incoming.contentKey) {
            frozen = null; frozenSoft = null; outgoing = target; incoming = target
            progress.snapTo(1f)
        } else if (target != incoming) {
            if (progress.value < 1f && layer.size.width > 0 && !layer.isReleased) {
                val image = layer.toImageBitmap()
                val soft = if (Build.VERSION.SDK_INT < 31 && blurThrough) {
                    withContext(Dispatchers.Default) { blurGlassSnapshot(image, minOf(image.width, image.height) * .045f) }
                } else null
                frozen = image; frozenSoft = soft
            } else { frozen = null; frozenSoft = null; outgoing = incoming }
            incoming = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (reduced) minOf(duration, 120) else duration, easing = LinearEasing))
            frozen = null; frozenSoft = null; outgoing = incoming
        }
    }
    val p = if (immediate) 1f else progress.value
    fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val blur = if (blurThrough) smooth(2f * minOf(p, 1f - p)) else 0f
    val reveal = if (blurThrough) smooth((p - .35f) / .3f) else smooth(p)
    @Composable fun Frame(frame: PlayerVisual, image: ImageBitmap? = null, soft: ImageBitmap? = null) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            renderEffect = if (Build.VERSION.SDK_INT >= 31 && blur > .001f) {
                val radius = minOf(size.width, size.height) * .045f * blur
                BlurEffect(radius, radius, TileMode.Clamp)
            } else null
        }) {
            if (image != null) Canvas(Modifier.matchParentSize()) { drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            else content(frame)
            if (Build.VERSION.SDK_INT < 31 && blur > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = blur }.clearAndSetSemantics {}) {
                if (soft != null) Canvas(Modifier.matchParentSize()) { drawImage(soft, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                else if (image == null && frame.softArtwork != null) content(frame.copy(artwork = frame.softArtwork))
            }
        }
    }
    Box(modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) {
        if (!immediate && p < 1f) Box(Modifier.matchParentSize().clearAndSetSemantics {}) { Frame(outgoing, frozen, frozenSoft) }
        Box(Modifier.matchParentSize().graphicsLayer { alpha = reveal }) { Frame(if (immediate) target else incoming) }
    }
}
