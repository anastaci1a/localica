package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.annotation.RequiresApi
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
import kotlin.math.*

internal val LocalArtworkTransition = staticCompositionLocalOf { ArtworkTransition.SoftWash }

/** Explicit opaque outgoing frame plus masked incoming frame. Interruptions flatten the
 * displayed composite once, rather than restarting from an old cover or stacking animations. */
@Composable
internal fun ArtworkBlend(visual: PlayerVisual, modifier: Modifier, duration: Int, immediate: Boolean = false, opacityBlend: Boolean = false,
    content: @Composable (PlayerVisual) -> Unit) {
    val target = visual.copy(loading = false)
    var outgoing by remember { mutableStateOf(target) }
    var incoming by remember { mutableStateOf(target) }
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(1f) }
    val layer = rememberGraphicsLayer()
    val reduced = LocalReducedMotion.current
    val style = LocalArtworkTransition.current
    LaunchedEffect(target, immediate) {
        if (immediate || target.contentKey != null && target.contentKey == incoming.contentKey) {
            frozen = null; outgoing = target; incoming = target
            progress.snapTo(1f)
        } else if (target != incoming) {
            if (progress.value < 1f && layer.size.width > 0 && !layer.isReleased) {
                frozen = layer.toImageBitmap()
            } else { frozen = null; outgoing = incoming }
            incoming = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (reduced) minOf(duration, 120) else duration, easing = FastOutSlowInEasing))
            frozen = null; outgoing = incoming
        }
    }
    Box(modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) {
        if (!immediate && progress.value < 1f) {
            val image = frozen
            if (image != null) Canvas(Modifier.matchParentSize()) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            } else Box(Modifier.matchParentSize().clearAndSetSemantics {}) { content(outgoing) }
        }
        val lens = remember { if (Build.VERSION.SDK_INT >= 33) ArtworkWashEffect() else null }
        val mask = remember { android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888) }
        val pixels = remember { IntArray(64 * 64) }
        Box(Modifier.matchParentSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            val p = if (immediate) 1f else progress.value
            if (reduced || opacityBlend) alpha = p
            else if (Build.VERSION.SDK_INT >= 33) renderEffect = lens?.effect(size.width, size.height, p, style)
        }.then(if (Build.VERSION.SDK_INT < 33 && !reduced && !opacityBlend) Modifier.drawWithContent {
            drawContent()
            val p = if (immediate) 1f else progress.value
            if (p < 1f) {
                for (y in 0 until 64) for (x in 0 until 64) pixels[y * 64 + x] =
                    (artworkCoverage(x / 63f, y / 63f, p, style) * 255).toInt().coerceIn(0, 255).shl(24) or 0xffffff
                mask.setPixels(pixels, 0, 64, 0, 0, 64, 64)
                drawImage(mask.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium, blendMode = BlendMode.DstIn)
            }
        } else Modifier)) { content(if (immediate) target else incoming) }
    }
}

internal fun artworkCoverage(x: Float, y: Float, progress: Float, style: ArtworkTransition): Float {
    if (progress <= 0f) return 0f
    if (progress >= 1f) return 1f
    val field = when (style) {
        ArtworkTransition.SoftWash -> x * .65f + y * .2f + .075f * sin(y * 8f + x * 3f) + .075f
        ArtworkTransition.FlowingDissolve -> .5f + .19f * sin(x * 7f + sin(y * 5f)) + .19f * cos(y * 8f - x * 3f)
        ArtworkTransition.GentleRipple -> sqrt((x - .42f).pow(2) + (y - .48f).pow(2)) * 1.15f
    }
    val t = ((progress * 1.4f - .2f - field + .18f) / .36f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@RequiresApi(33)
private class ArtworkWashEffect {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float progress;
        uniform float style;
        half4 main(float2 p) {
            if (progress <= 0.0) return half4(0);
            if (progress >= 1.0) return scene.eval(p);
            float2 uv = p / max(extent, float2(1));
            float field;
            if (style < 0.5) field = uv.x*.65 + uv.y*.2 + .075*sin(uv.y*8.0+uv.x*3.0)+.075;
            else if (style < 1.5) field = .5+.19*sin(uv.x*7.0+sin(uv.y*5.0))+.19*cos(uv.y*8.0-uv.x*3.0);
            else field = length(uv-float2(.42,.48))*1.15;
            float alpha = smoothstep(-.18,.18,progress*1.4-.2-field);
            float edge = 4.0 * alpha * (1.0-alpha);
            float2 direction = style > 1.5 ? normalize(uv-float2(.419,.479)) : float2(sin(uv.y*8.0),cos(uv.x*6.0));
            float2 warp = direction * min(extent.x,extent.y) * .012 * edge * sin(progress*3.14159);
            return scene.eval(clamp(p+warp,float2(0),extent)) * half(alpha);
        }
    """.trimIndent())
    fun effect(width: Float, height: Float, progress: Float, style: ArtworkTransition): RenderEffect {
        shader.setFloatUniform("extent", width, height)
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("style", style.ordinal.toFloat())
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
    }
}
