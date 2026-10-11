package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

internal val NavigationPillHeight = 64.dp
internal val CompactControlSize = NavigationPillHeight * .8f
internal val MiniArtworkSize = 32.dp

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(onPressed: (Boolean) -> Unit = {}): Modifier {
    val notify by rememberUpdatedState(onPressed)
    val reduced = LocalReducedMotion.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed && !reduced) 1.045f else 1f,
        spring(dampingRatio = .62f, stiffness = 650f), label = "Glass pressure")
    return graphicsLayer { scaleX = scale; scaleY = 1f + (scale - 1f) * .65f }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true; notify(true)
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Final) }
                while (event.changes.any { it.pressed })
            } finally { pressed = false; notify(false) }
        }
    }
}

/** Clear lens over a separately recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {}) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dark = source?.dark == true
    var pressed by remember { mutableStateOf(false) }
    val energy by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 650f), label = "Glass illumination")
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) GlassLens() else null }
    BoxWithConstraints(modifier.glassPress { pressed = it }.clearGlassShadow().clip(CircleShape)
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (source != null && Build.VERSION.SDK_INT >= 33) {
            // Padding supplies actual neighboring pixels along the curved silhouette.
            val padding = 24.dp
            Canvas(Modifier.requiredSize(maxWidth + padding * 2, maxHeight + padding * 2)
                .graphicsLayer { renderEffect = lens?.effect(size.width, size.height, padding.toPx()) }) {
                val offset = source.origin - origin + Offset(padding.toPx(), padding.toPx())
                translate(offset.x, offset.y) { if (!source.layer.isReleased) drawLayer(source.layer) }
            }
        }
        // Older Android uses direct transparency, never the frosted snapshot fallback.
        Canvas(Modifier.matchParentSize()) {
            drawRect(tint)
            val radius = size.minDimension / 2
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .85f + .15f * energy), Color.White.copy(alpha = .06f),
                Color.Black.copy(alpha = .2f), Color.White.copy(alpha = .7f))), cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
            val inset = 2.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f + .3f * energy), Color.Transparent, Color.White.copy(alpha = .13f))),
                topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(2.dp.toPx()))
        }
        CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else Color(0xFF202124)) { content() }
    }
}

/** Ordinary elevation paints a dark silhouette through a transparent center. Exclude it. */
private fun Modifier.clearGlassShadow() = drawBehind {
    val radius = size.minDimension / 2
    val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
    clipPath(body, ClipOp.Difference) {
        for (step in 6 downTo 1) {
            val spread = step.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = .018f), Offset(-spread, 2.dp.toPx() - spread),
                androidx.compose.ui.geometry.Size(size.width + 2 * spread, size.height + 2 * spread), CornerRadius(radius + spread))
        }
    }
}

@RequiresApi(33)
private class GlassLens {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float padding;
        half4 main(float2 p) {
            float2 center = extent * 0.5;
            float2 halfSize = center - padding;
            float radius = min(halfSize.x, halfSize.y);
            float2 local = p - center;
            float2 axis = clamp(local, -max(halfSize-radius, float2(0)), max(halfSize-radius, float2(0)));
            float2 radial = local - axis;
            float len = max(length(radial), 0.001);
            float depth = radius - len;
            float t = clamp(1.0 - depth / max(radius * 0.64, 1.0), 0.0, 1.0);
            float slope = t * t / sqrt(max(1.02 - t * t, 0.08));
            float bend = min(radius * 0.8, slope * radius * 0.42);
            float2 normal = radial / len;
            half4 color = scene.eval(p - normal * bend);
            float light = dot(normal, normalize(float2(-0.55, -1.0)));
            float rim = pow(t, 12.0);
            color.rgb = mix(color.rgb, half3(color.a), half(max(light, 0.0) * rim * 0.55));
            color.rgb *= half(1.0 - max(-light, 0.0) * rim * 0.16);
            return color;
        }
    """.trimIndent())
    private var dimensions = Triple(0f, 0f, 0f)
    private var cached: RenderEffect? = null
    fun effect(width: Float, height: Float, padding: Float): RenderEffect? {
        if (width <= padding * 2 || height <= padding * 2) return null
        val next = Triple(width, height, padding)
        if (next != dimensions) {
            dimensions = next
            shader.setFloatUniform("extent", width, height)
            shader.setFloatUniform("padding", padding)
            cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        }
        return cached
    }
}
