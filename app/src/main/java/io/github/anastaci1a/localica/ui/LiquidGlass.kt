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
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal val NavigationPillHeight = 64.dp
internal val CompactControlSize = NavigationPillHeight * .8f
internal const val RaisedGlassDepthScale = 1.3f
internal const val BottomPillDepthScale = .72f
internal val MiniArtworkSize = 32.dp

@Stable
internal class GlassContact {
    var pressed by mutableStateOf(false)
    var travel by mutableFloatStateOf(0f)
    lateinit var body: State<GlassShape>
}

@Composable
internal fun rememberGlassContact(): GlassContact {
    val contact = remember { GlassContact() }
    contact.body = rememberGlassBody({ contact.travel }, contact = { if (contact.pressed) 1f else 0f })
    return contact
}

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(contact: GlassContact = rememberGlassContact(), onPressed: (Boolean) -> Unit = {}): Modifier {
    return drawWithContent {
        scale(contact.body.value.scaleX(), contact.body.value.scaleY()) { this@drawWithContent.drawContent() }
    }.observeGlassContact { contact.travel = 0f; contact.pressed = it; onPressed(it) }
}

/** Shared contact lifetime for ordinary buttons and independently draggable glass bodies. */
@Composable
internal fun Modifier.observeGlassContact(onPressed: (Boolean) -> Unit): Modifier {
    val notify by rememberUpdatedState(onPressed)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            notify(true)
            try {
                // Visual pressure belongs to the initiating finger until it lifts.
                // Clickable still cancels activation when that finger leaves the target.
                do { val event = awaitPointerEvent(PointerEventPass.Final) }
                while (event.changes.any { it.id == down.id && it.pressed })
            } finally { notify(false) }
        }
    }
}

/** Adjustable lens over a separately recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    cornerRadius: Dp? = null, frostProgress: Float = 0f, pressFeedback: Boolean = true, refraction: Float = 1f, inkOverride: Color? = null, protectCenter: Boolean = false, shadow: Boolean = true,
    body: () -> GlassShape = { GlassShape() }, panelProgress: Float = 0f, panelDark: Boolean? = null, depthScale: Float = modifier.glassDepthScale(),
    content: @Composable BoxScope.() -> Unit = {}) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current.normalized()
    val samplePixels = remember(source?.snapshot) { source?.snapshot?.toPixelMap() }
    val sample = samplePixels?.let { pixels -> remember(pixels, origin, surfaceSize, source?.size) {
        source?.let { source ->
            var total = 0f
            for (dy in 1..3) for (dx in 1..3) {
                val x = ((origin.x + surfaceSize.width * dx / 4f - source.origin.x) / source.size.width.coerceAtLeast(1) * pixels.width).toInt().coerceIn(0, pixels.width - 1)
                val y = ((origin.y + surfaceSize.height * dy / 4f - source.origin.y) / source.size.height.coerceAtLeast(1) * pixels.height).toInt().coerceIn(0, pixels.height - 1)
                total += pixels[x, y].luminance()
            }
            total / 9
        }
    } }
    var sampledDark by remember(source) { mutableStateOf(source?.dark == true) }
    val filteredLight by animateFloatAsState(sample ?: if (sampledDark) 0f else 1f,
        tween(if (LocalReducedMotion.current) 0 else 120), label = "Glass contrast sampling")
    SideEffect {
        if (sample != null) {
            if (filteredLight < .25f) sampledDark = true
            else if (filteredLight > .55f) sampledDark = false
        }
    }
    val dark = inkOverride?.let { it.luminance() > .5f } ?: if (appearance.whiteTint >= .45f) false
        else if (appearance.contrast > 0f && panelProgress == 0f) sampledDark else source?.dark == true
    val frost = frostProgress.coerceIn(0f, 1f)
    val panel = panelProgress.coerceIn(0f, 1f)
    val coatingDark = panelDark ?: dark
    val panelBase = if (coatingDark) Color.Black else androidx.compose.material3.MaterialTheme.colorScheme.surface
    val contact = rememberGlassContact()
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) GlassLens() else null }
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress(contact) else Modifier)
        .then(if (shadow) Modifier.clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, cornerRadius) else Modifier)
        .clip(cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape)
        .onGloballyPositioned { origin = it.backdropPosition(view); surfaceSize = it.size }) {
        if (source != null && Build.VERSION.SDK_INT >= 31) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val blur = with(density) { 22.dp.toPx() } * (appearance.blur + (1f - appearance.blur) * frost)
            val padding = with(density) { 32.dp.toPx() }
            FilteredBackdrop(Modifier.matchParentSize(), source, { origin }, if (blur > 1f) .5f else 1f, padding) { extent, resolution ->
                val pad = padding * resolution
                val radius = (cornerRadius?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                if (Build.VERSION.SDK_INT >= 33) lens?.effect(extent.width, extent.height, pad, blur * resolution,
                    radius, appearance.contrast * (1f - frost), dark, refraction, protectCenter,
                    appearance.depth * depthScale * body().thickness * (if (pressFeedback) contact.body.value.thickness else 1f),
                    appearance.whiteTint * (1f - panel))
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
            if (Build.VERSION.SDK_INT < 33 || source == null) drawRect(Color.White.copy(alpha = appearance.whiteTint * (1f - panel)))
            drawRect(tint)
            if (panel > 0f) drawFrostCoating(coatingDark, panelBase, Color.Transparent, .5f, panel)
            if (Build.VERSION.SDK_INT < 33) drawRect((if (dark) Color.Black else Color.White)
                .copy(alpha = appearance.contrast * .26f * (1f - frost)))
            val radius = (cornerRadius?.toPx() ?: (size.minDimension / 2)).coerceIn(0f, size.minDimension / 2)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .06f),
                Color.Black.copy(alpha = .2f), Color.White.copy(alpha = .7f))), cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
        }
        CompositionLocalProvider(LocalContentColor provides (inkOverride ?: if (dark) Color.White else Color(0xFF202124))) { content() }
    }
}

@RequiresApi(33)
private class GlassLens {
    private val shader = android.graphics.RuntimeShader(WaterCapOptics + """
        uniform shader scene;
        uniform float2 extent;
        uniform float padding;
        uniform float corner;
        uniform float contrast;
        uniform float darkInk;
        uniform float power;
        uniform float flatCenter;
        uniform float thickness;
        uniform float whiteTint;
        half4 main(float2 p) {
            float2 center = extent * 0.5;
            float2 halfSize = center - padding;
            float radius = max(corner, 0.001);
            float2 local = p - center;
            float2 axis = clamp(local, -max(halfSize-radius, float2(0)), max(halfSize-radius, float2(0)));
            float2 radial = local - axis;
            float len = max(length(radial), 0.001);
            float t = clamp(len / radius, 0.0, 1.0);
            // A rounded cap plus a shallow axial crown: no flat plateau or bevel.
            // Height and its analytic derivative own both refraction and illumination.
            float2 q = local / max(halfSize, float2(1));
            float f = max(1.0 - t * t, 0.0004);
            float g = max(1.0 - 0.125 * dot(q, q), 0.5);
            float cap = radius * thickness * 0.55;
            float height = cap * sqrt(f * g);
            float2 gradient = cap * (g * (-2.0 * radial / (radius * radius))
                - f * 0.25 * local / max(halfSize * halfSize, float2(1))) / (2.0 * sqrt(f * g));
            float3 surfaceNormal = normalize(float3(-gradient, 1.0));
            // Air-to-water Snell refraction, projected onto the backdrop plane.
            float2 displacement = capDisplacement(surfaceNormal, height);
            float2 normal = radial / len;
            float safeCenter = mix(1.0, smoothstep(halfSize.x * 0.28, halfSize.x * 0.72, abs(local.x)), flatCenter);
            half4 color = scene.eval(p + displacement * power * safeCenter);
            // Monotonic shadow lift. Luma-dependent alpha blending can fold a ramp
            // back on itself; use an ordered transfer curve, then preserve chroma.
            float backdropLuma = dot(float3(color.rgb), float3(0.2126, 0.7152, 0.0722)) / max(float(color.a), 0.001);
            float luma = clamp(backdropLuma, 0.0, 1.0);
            float lifted = 1.0 - (1.0 - luma) * (1.0 - whiteTint) / max(1.0 - whiteTint * luma, 0.0001);
            float lift = (lifted - luma) / max(1.0 - luma, 0.001);
            color.rgb = mix(color.rgb, half3(color.a), half(clamp(lift, 0.0, 1.0)));
            float light = dot(surfaceNormal, normalize(float3(-0.55, -1.0, 1.2)));
            float fresnel = capFresnel(surfaceNormal);
            color.rgb = mix(color.rgb, half3(color.a), half(max(light, 0.0) * fresnel * power * 0.55));
            color.rgb *= half(1.0 - max(-light, 0.0) * fresnel * power * 0.12);
            // Constant bounded support preserves ordering, including translucent pixels.
            color.rgb = mix(color.rgb, half3(darkInk * float(color.a)), half(contrast * 0.72));
            return color;
        }
    """.trimIndent())
    private var dimensions = listOf<Float>()
    private var cached: RenderEffect? = null
    fun effect(width: Float, height: Float, padding: Float, blur: Float, radius: Float, contrast: Float, dark: Boolean, power: Float, protectCenter: Boolean, thickness: Float, whiteTint: Float): RenderEffect? {
        if (width <= padding * 2 || height <= padding * 2) return null
        val next = listOf(width, height, padding, blur, radius, contrast, if (dark) 0f else 1f, power, if (protectCenter) 1f else 0f, thickness, whiteTint)
        if (next != dimensions) {
            dimensions = next
            shader.setFloatUniform("extent", width, height)
            shader.setFloatUniform("padding", padding)
            shader.setFloatUniform("corner", radius)
            shader.setFloatUniform("contrast", contrast)
            shader.setFloatUniform("power", power)
            shader.setFloatUniform("thickness", thickness)
            shader.setFloatUniform("whiteTint", whiteTint)
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
