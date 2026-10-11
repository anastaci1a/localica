package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/** A capsule lens over a separate recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {}) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dark = source?.dark == true
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) GlassLens() else null }
    val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = .9f),
        Color.White.copy(alpha = .12f), Color.White.copy(alpha = .55f)))
    Box(modifier.shadow(3.dp, CircleShape, clip = false).clip(CircleShape)
        .onGloballyPositioned { origin = it.positionInRoot() }
        .border(.75.dp, edge, CircleShape)) {
        if (source != null) Canvas(Modifier.matchParentSize().graphicsLayer {
            renderEffect = when {
                Build.VERSION.SDK_INT >= 33 -> lens?.effect(size.width, size.height, density)
                Build.VERSION.SDK_INT >= 31 -> BlurEffect(8.dp.toPx(), 8.dp.toPx(), TileMode.Clamp)
                else -> null
            }
        }) {
            val offset = source.origin - origin
            translate(offset.x, offset.y) {
                if (Build.VERSION.SDK_INT >= 31) drawLayer(source.layer)
                else source.fallback?.let { drawImage(it, dstSize = source.size, filterQuality = FilterQuality.Medium) }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val base = if (dark) Color(0xFF141C26) else Color.White
            drawRect(base.copy(alpha = if (source == null) .88f else if (dark) .22f else .38f))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f),
                Color.White.copy(alpha = .025f), Color.White.copy(alpha = .08f))))
            drawRect(tint)
            val inset = 1.5.dp.toPx()
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .24f), Color.Transparent,
                Color.Black.copy(alpha = .07f))), topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((size.minDimension / 2 - inset).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
        }
        CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else Color(0xFF202124)) { content() }
    }
}

@RequiresApi(33)
private class GlassLens {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float density;
        half4 main(float2 p) {
            float2 center = extent * 0.5;
            float radius = min(center.x, center.y);
            float2 local = p - center;
            float2 axis = float2(clamp(local.x, -max(center.x-radius, 0.0), max(center.x-radius, 0.0)),
                                 clamp(local.y, -max(center.y-radius, 0.0), max(center.y-radius, 0.0)));
            float2 radial = local - axis;
            float radialLength = max(length(radial), 0.001);
            float depth = max(radius - radialLength, 0.0);
            float bend = min(radius * 0.22, 7.0 * density) * exp(-depth / max(radius * 0.24, 1.0));
            float2 samplePoint = center + local / 1.025 - radial / radialLength * bend;
            return scene.eval(clamp(samplePoint, float2(0.0), extent));
        }
    """.trimIndent())
    private var dimensions = Triple(0f, 0f, 0f)
    private var cached: RenderEffect? = null
    fun effect(width: Float, height: Float, density: Float): RenderEffect? {
        if (width <= 0f || height <= 0f) return null
        val next = Triple(width, height, density)
        if (next != dimensions) {
            dimensions = next
            shader.setFloatUniform("extent", width, height)
            shader.setFloatUniform("density", density)
            cached = android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene"),
                android.graphics.RenderEffect.createBlurEffect(8f * density, 8f * density, android.graphics.Shader.TileMode.CLAMP)
            ).asComposeRenderEffect()
        }
        return cached
    }
}
