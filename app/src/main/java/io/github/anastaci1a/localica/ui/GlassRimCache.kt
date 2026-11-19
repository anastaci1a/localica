package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import io.github.anastaci1a.localica.ui.spec.FlexGlow

internal fun glowWhite(luminance: Float): Float {
    val p = Math.pow(luminance.toDouble(), 1.25)
    return java.math.BigDecimal(1 - p).multiply(java.math.BigDecimal(.9)).add(java.math.BigDecimal(p * .25)).toDouble().toFloat()
}

/** One draw owner's current preparation; paths are replaced, never rewritten after publication. */
internal class GlassRimCache {
    private val effect by lazy { if (android.os.Build.VERSION.SDK_INT >= 33) ContactLightEffect() else null }
    fun meanEffect(background: Color) = if (android.os.Build.VERSION.SDK_INT >= 33) effect?.mean(background) else null
    fun effect(glow: GlassGlow, scale: Offset, bounds: Rect, density: Float, background: Color, height: Float, corner: Float, translation: Offset = Offset.Zero): RenderEffect? =
        if (android.os.Build.VERSION.SDK_INT >= 33 && !bounds.isEmpty) effect?.effect(glow, scale, bounds, density, mask(glowReducedRadius(FlexGlow.radius(minOf(bounds.width, bounds.height), density))), background, height, corner, translation) else null
    private var center: Offset? = null
    private var tone: GlassTone? = null
    private var brushes: GlassRimBrushes? = null
    private var bounds: Rect? = null
    private var radius = Float.NaN
    private var path: Path? = null
    private var maskRadius = Float.NaN
    private var mask: ImageBitmap? = null
    val composite = Paint()
    private var luminance = Float.NaN; private var white = 0f
    fun white(value: Float): Float {
        if (luminance != value) { luminance = value; white = glowWhite(value) }
        return white
    }
    private var paintWhite = Float.NaN
    private var localPaint: Paint? = null
    private val broadPaint by lazy { Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(glowMatrix(false, 0f).also { for (i in listOf(4, 9, 14)) it[i] *= 255f })) } }

    fun paint(local: Boolean, white: Float): Paint {
        if (!local) return broadPaint
        if (paintWhite != white) {
            paintWhite = white
            localPaint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(glowMatrix(true, white))) }
        }
        return requireNotNull(localPaint)
    }

    fun mask(sigma: Float): ImageBitmap {
        if (maskRadius != sigma) {
            maskRadius = sigma
            val side = glowDiskSide(sigma)
            mask = android.graphics.Bitmap.createBitmap(glowDisk(sigma), side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        return requireNotNull(mask)
    }

    fun brushes(center: Offset, tone: GlassTone): GlassRimBrushes {
        if (this.center == center && this.tone == tone) brushes?.let { return it }
        return glassRimBrushes(center, tone).also {
            this.center = center
            this.tone = tone
            brushes = it
        }
    }

    fun roundedPath(bounds: Rect, radius: Float): Path {
        if (this.bounds == bounds && this.radius == radius) path?.let { return it }
        return Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }.also {
            this.bounds = bounds
            this.radius = radius
            path = it
        }
    }
}

internal data class GlassRimBrushes(val shine: Brush, val shade: Brush)

internal fun glassRimBrushes(center: Offset, tone: GlassTone): GlassRimBrushes {
    val white = tone.color(Color.White)
    val black = tone.color(Color.Black)
    // Diagonally opposed highlights taper into quiet, slightly shaded corners.
    // The silhouette clips both strokes inward; neither changes the hit geometry.
    val shine = Brush.sweepGradient(
        0f to white.copy(alpha = .025f), .125f to white.copy(alpha = .32f),
        .25f to white.copy(alpha = .025f), .375f to Color.Transparent,
        .5f to white.copy(alpha = .025f), .625f to white.copy(alpha = .32f),
        .75f to white.copy(alpha = .025f), .875f to Color.Transparent,
        1f to white.copy(alpha = .025f), center = center)
    val shade = Brush.sweepGradient(
        0f to Color.Transparent, .125f to Color.Transparent, .25f to Color.Transparent,
        .375f to black.copy(alpha = .11f), .5f to Color.Transparent,
        .625f to Color.Transparent, .75f to Color.Transparent, .875f to black.copy(alpha = .11f),
        1f to Color.Transparent, center = center)
    return GlassRimBrushes(shine, shade)
}

@androidx.annotation.RequiresApi(33)
private class ContactLightEffect {
    private var previous: List<Any>? = null; private var cached: RenderEffect? = null
    private var meanBackground: Color? = null; private var meanEffect: RenderEffect? = null
    private val matrices = listOf(glowMatrix(false, 0f), glowMatrix(true, 0f))
    private var image: ImageBitmap? = null; private var sampler: android.graphics.BitmapShader? = null
    private val passes = mutableListOf<android.graphics.RuntimeShader>()
    private val mean = android.graphics.RuntimeShader("""
        uniform shader scene; uniform float3 background;
        float3 linear(float3 c) { return mix(c / 12.92, pow((c + .055) / 1.055, float3(2.4)), step(float3(.04045), c)); }
        half4 main(float2 p) {
            float l = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                float4 c = float4(scene.eval(float2(float(x) + .5, float(y) + .5)));
                l += dot(linear(c.rgb + background * (1 - c.a)), float3(.2126, .7152, .0722));
            }
            return half4(half3(l / 64), 1);
        }
    """.trimIndent())
    fun mean(background: Color): RenderEffect {
        if (meanBackground == background) return requireNotNull(meanEffect)
        meanBackground = background
        mean.setFloatUniform("background", background.red, background.green, background.blue)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(mean, "scene").asComposeRenderEffect().also { meanEffect = it }
    }
    fun effect(glow: GlassGlow, scale: Offset, bounds: Rect, density: Float,
        mask: ImageBitmap, background: Color, height: Float, corner: Float, translation: Offset): RenderEffect? {
        val key = listOf(glow, scale, bounds, density, mask, background, height, corner, translation)
        if (key == previous) return cached
        previous = key
        var result: android.graphics.RenderEffect? = null
        if (image !== mask) { image = mask; sampler = android.graphics.BitmapShader(mask.asAndroidBitmap(), android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL).apply { setFilterMode(android.graphics.BitmapShader.FILTER_MODE_LINEAR) } }
        var slot = 0
        for (index in -1..glow.retiring.size) {
            val frame = if (index in glow.retiring.indices) glow.retiring[index] else glow
            val local = index >= 0; val alpha = if (local) frame.little else frame.big
            if (alpha <= 0f) continue
            while (passes.size <= slot) passes.add(android.graphics.RuntimeShader("""
                uniform shader scene; uniform shader mask;
                uniform float4 row0, row1, row2, bounds, light;
                uniform float3 background; uniform float2 extent; uniform float corner, alpha, local;
                float3 clean(float3 x) { return sign(x) * floor(abs(x) / .0001 + .5) * .0001; }
                half4 main(float2 p) {
                    float4 raw = float4(scene.eval(p));
                    if (p.y >= extent.y) return half4(raw);
                    float2 q = abs(p - (bounds.xy + bounds.zw) / 2) - (bounds.zw - bounds.xy) / 2 + corner;
                    float edge = length(max(q, float2(0))) + min(max(q.x, q.y), 0) - corner;
                    if (edge >= .5) return half4(raw);
                    float3 d = raw.rgb + background * (1 - raw.a);
                    float3 a = row0.xyz, b = row1.xyz, c = row2.xyz;
                    if (local > .5) {
                        float l = clamp(float(scene.eval(float2(.5, extent.y + .5)).r), 0, 1);
                        float w = (1 - pow(l, 1.25)) * .9 + pow(l, 1.25) * .25;
                        float v = w <= .04045 ? w / 12.92 : pow((w + .055) / 1.055, 2.4);
                        v = v <= .0031308 ? 12.92 * v : 1.055 * pow(v, 1 / 2.4) - .055;
                        v = min(v, .99609375); float g = floor((v / (1 - v) + 1) / .0001 + .5) * .0001;
                        a = clean(a * g); b = clean(b * g); c = clean(c * g);
                    }
                    float3 y = clamp(float3(dot(a, d) + row0.w, dot(b, d) + row1.w, dot(c, d) + row2.w), -.75, 1);
                    float coverage = local > .5 ? float(mask.eval((p - light.xy) / light.z + light.ww / 2).a) : 1;
                    float weight = clamp(alpha, 0, 1) * coverage * clamp(.5 - edge, 0, 1);
                    return half4(half3(clamp(mix(d, y, weight), 0, 1) * raw.a), half(raw.a));
                }
            """.trimIndent()))
            val shader = passes[slot++]; val matrix = matrices[if (local) 1 else 0]
            for (row in 0..2) shader.setFloatUniform("row$row", matrix[row * 5], matrix[row * 5 + 1], matrix[row * 5 + 2], matrix[row * 5 + 4])
            val radius = FlexGlow.radius(minOf(bounds.width, bounds.height), density)
            val point = glowCentre(frame.point, bounds, scale, translation)
            shader.setFloatUniform("light", point.x, point.y, radius / glowReducedRadius(radius) * frame.scale, mask.width.toFloat())
            shader.setFloatUniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
            shader.setFloatUniform("extent", bounds.right, height); shader.setFloatUniform("corner", corner)
            shader.setFloatUniform("background", background.red, background.green, background.blue)
            shader.setFloatUniform("alpha", alpha); shader.setFloatUniform("local", if (local) 1f else 0f)
            shader.setInputShader("mask", requireNotNull(sampler))
            val next = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
            result = result?.let { android.graphics.RenderEffect.createChainEffect(next, it) } ?: next
        }
        while (passes.size > slot) passes.removeAt(passes.lastIndex)
        return result?.asComposeRenderEffect().also { cached = it }
    }
}
