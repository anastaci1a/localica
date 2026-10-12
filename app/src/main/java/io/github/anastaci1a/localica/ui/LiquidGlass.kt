package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal val NavigationPillHeight = 64.dp
internal val CompactControlSize = NavigationPillHeight * .8f
internal val MiniArtworkSize = 32.dp

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(onPressed: (Boolean) -> Unit = {}): Modifier {
    val notify by rememberUpdatedState(onPressed)
    val reduced = LocalReducedMotion.current
    var pressed by remember { mutableStateOf(false) }
    var travel by remember { mutableFloatStateOf(0f) }
    val strain = rememberGlassElastic({ travel }, { if (pressed) .035f else 0f })
    return drawWithContent {
        val value = if (reduced) 0f else strain.value
        scale(kotlin.math.exp(value), kotlin.math.exp(-value)) { this@drawWithContent.drawContent() }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            travel = 0f
            pressed = true; notify(true)
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.firstOrNull { it.id == down.id }?.let { travel = (it.position.x - down.position.x) / size.width.coerceAtLeast(1) }
                }
                while (event.changes.any { it.pressed })
            } finally { pressed = false; notify(false) }
        }
    }
}

/** Adjustable lens over a separately recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    cornerRadius: Dp? = null, frostProgress: Float = 0f, pressFeedback: Boolean = true, refraction: Float = 1f, inkOverride: Color? = null, protectCenter: Boolean = false, shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current.normalized()
    val samplePixels = remember(source?.snapshot) { source?.snapshot?.toPixelMap() }
    val sample = samplePixels?.let { pixels -> remember(pixels, origin, source?.size) {
        source?.let { source ->
            val x = ((origin.x - source.origin.x) / source.size.width.coerceAtLeast(1) * pixels.width).toInt().coerceIn(0, pixels.width - 1)
            val y = ((origin.y - source.origin.y) / source.size.height.coerceAtLeast(1) * pixels.height).toInt().coerceIn(0, pixels.height - 1)
            pixels[x, y].luminance()
        }
    } }
    val dark = inkOverride?.let { it.luminance() > .5f } ?: if (appearance.whiteTint >= .45f) false
        else if (appearance.contrast > 0f) sample?.let { it < .35f } ?: (source?.dark == true) else source?.dark == true
    val frost = frostProgress.coerceIn(0f, 1f)
    var pressed by remember { mutableStateOf(false) }
    val energy by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 650f), label = "Glass illumination")
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) GlassLens() else null }
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress { pressed = it } else Modifier)
        .then(if (shadow) Modifier.clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, cornerRadius) else Modifier)
        .clip(cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape)
        .onGloballyPositioned { origin = it.backdropPosition(view) }) {
        if (source != null && Build.VERSION.SDK_INT >= 31) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val blur = with(density) { 22.dp.toPx() } * (appearance.blur + (1f - appearance.blur) * frost)
            val padding = with(density) { 32.dp.toPx() }
            FilteredBackdrop(Modifier.matchParentSize(), source, { origin }, if (blur > 1f) .5f else 1f, padding) { extent, resolution ->
                val pad = padding * resolution
                val radius = (cornerRadius?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                if (Build.VERSION.SDK_INT >= 33) lens?.effect(extent.width, extent.height, pad, blur * resolution,
                    radius, appearance.contrast * (1f - frost), dark, refraction, protectCenter)
                else if (blur > 0f) BlurEffect(blur * resolution, blur * resolution, TileMode.Clamp) else null
            }
        }

        if (source != null && Build.VERSION.SDK_INT < 31 && (appearance.blur > 0f || frost > 0f)) {
            Canvas(Modifier.matchParentSize()) {
                val offset = source.origin - origin
                translate(offset.x, offset.y) {
                    if (appearance.blur > 0f) source.glassFallback?.let { drawImage(it, dstSize = source.size, filterQuality = FilterQuality.Medium) }
                    // Blend shared prepared endpoints; no bitmap processing during a gesture.
                    if (frost > 0f) source.fallback?.let { drawImage(it, dstSize = source.size,
                        filterQuality = FilterQuality.Medium, alpha = frost) }
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color.White.copy(alpha = appearance.whiteTint))
            drawRect(tint)
            if (Build.VERSION.SDK_INT < 33) drawRect((if (dark) Color.Black else Color.White)
                .copy(alpha = appearance.contrast * .26f * (1f - frost)))
            val radius = (cornerRadius?.toPx() ?: (size.minDimension / 2)).coerceIn(0f, size.minDimension / 2)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .85f + .15f * energy), Color.White.copy(alpha = .06f),
                Color.Black.copy(alpha = .2f), Color.White.copy(alpha = .7f))), cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
            val inset = 2.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f + .3f * energy), Color.Transparent, Color.White.copy(alpha = .13f))),
                topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(2.dp.toPx()))
        }
        CompositionLocalProvider(LocalContentColor provides (inkOverride ?: if (dark) Color.White else Color(0xFF202124))) { content() }
    }
}

@RequiresApi(33)
private class GlassLens {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float padding;
        uniform float corner;
        uniform float contrast;
        uniform float darkInk;
        uniform float power;
        uniform float flatCenter;
        half4 main(float2 p) {
            float2 center = extent * 0.5;
            float2 halfSize = center - padding;
            float radius = max(corner, 0.001);
            float2 local = p - center;
            float2 axis = clamp(local, -max(halfSize-radius, float2(0)), max(halfSize-radius, float2(0)));
            float2 radial = local - axis;
            float len = max(length(radial), 0.001);
            float depth = radius - len;
            float t = clamp(1.0 - depth / max(radius * 0.64, 1.0), 0.0, 1.0);
            float slope = t * t / sqrt(max(1.02 - t * t, 0.08));
            float bend = min(radius * 0.8, slope * radius * 0.42);
            float2 normal = radial / len;
            float safeCenter = mix(1.0, smoothstep(halfSize.x * 0.28, halfSize.x * 0.72, abs(local.x)), flatCenter);
            half4 color = scene.eval(p - normal * bend * power * safeCenter);
            float light = dot(normal, normalize(float2(-0.55, -1.0)));
            float rim = pow(t, 12.0) * power;
            color.rgb = mix(color.rgb, half3(color.a), half(max(light, 0.0) * rim * 0.55));
            color.rgb *= half(1.0 - max(-light, 0.0) * rim * 0.16);
            float luminance = dot(float3(color.rgb), float3(0.2126, 0.7152, 0.0722));
            float correction = contrast * 0.65 * (darkInk > 0.5 ? 1.0 - luminance : luminance);
            color.rgb = mix(color.rgb, half3(darkInk * float(color.a)), half(correction));
            return color;
        }
    """.trimIndent())
    private var dimensions = listOf<Float>()
    private var cached: RenderEffect? = null
    fun effect(width: Float, height: Float, padding: Float, blur: Float, radius: Float, contrast: Float, dark: Boolean, power: Float, protectCenter: Boolean): RenderEffect? {
        if (width <= padding * 2 || height <= padding * 2) return null
        val next = listOf(width, height, padding, blur, radius, contrast, if (dark) 0f else 1f, power, if (protectCenter) 1f else 0f)
        if (next != dimensions) {
            dimensions = next
            shader.setFloatUniform("extent", width, height)
            shader.setFloatUniform("padding", padding)
            shader.setFloatUniform("corner", radius)
            shader.setFloatUniform("contrast", contrast)
            shader.setFloatUniform("power", power)
            shader.setFloatUniform("flatCenter", if (protectCenter) 1f else 0f)
            shader.setFloatUniform("darkInk", if (dark) 0f else 1f)
            val refraction = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
            // Scatter the sampled backdrop before applying lens curvature and its sharp rim.
            cached = (if (blur > 0f) android.graphics.RenderEffect.createChainEffect(refraction,
                android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP))
                else refraction).asComposeRenderEffect()
        }
        return cached
    }
}
