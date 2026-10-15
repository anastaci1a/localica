package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Compact input: the reachable backdrop region followed by the visible foreground.
 * Coordinates stay in the output crop, independently of the backdrop's bounds. */
internal data class LensAtlas(val output: Size, val backdrop: Size,
    val backdropOffset: Offset, val foregroundY: Float)

/** One material kernel for a control, expanded task or connected selection. The
 * geometry adapter supplies distance/normals; all three get identical optics. */
@RequiresApi(33)
internal class LensRenderer {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform shader field;
        uniform float4 bounds;
        uniform float corner;
        uniform float span;
        uniform float3 optics;
        uniform float power;
        uniform float boundaryEnabled;
        uniform float whiteTint;
        uniform float illumination;
        uniform float3 tone;
        uniform float fieldMode;
        uniform float2 fieldOrigin;
        uniform float fieldScale;
        uniform float fieldRange;
        uniform float foregroundOffset;
        uniform float2 outputSize;
        uniform float2 backdropSize;
        uniform float2 backdropOffset;
        $GlassColorShader
        $BoundaryOpticsShader
        float3 geometry(float2 p) {
            if (fieldMode > 0.5) {
                float3 f = float3(field.eval((p - fieldOrigin) * fieldScale).rgb);
                return float3(f.r * fieldRange, f.gb * 2.0 - 1.0);
            }
            float2 middle = (bounds.xy + bounds.zw) * 0.5;
            float2 halfSize = (bounds.zw - bounds.xy) * 0.5;
            float2 local = p - middle;
            if (dot(local, local) < 0.0000001) return float3(span, 0, 0);
            float2 core = halfSize - corner;
            float2 q = max(abs(local) - core, float2(0));
            float outside = max(max(abs(local.x) - halfSize.x, abs(local.y) - halfSize.y), length(q) - corner);
            if (outside > 0.0) return float3(-outside, 0, 0);
            float2 side = max(1.0 - local * local / (halfSize * halfSize), float2(0));
            float rectangle = side.x * side.y;
            float2 rectangleGradient = -2.0 * local * side.yx / (halfSize * halfSize);
            float roundWeight = corner * corner / (halfSize.x * halfSize.y);
            float rounded = corner > 0.0 ? max(1.0 - dot(q, q) / (corner * corner), 0.0) : 1.0;
            float2 roundedGradient = corner > 0.0 ? -2.0 * sign(local) * q / (corner * corner) : float2(0);
            float denominator = (1.0 - roundWeight) * rounded + roundWeight * rectangle;
            float potential;
            float2 gradient;
            if (denominator > 0.0000001) {
                potential = rectangle * rounded / denominator;
                gradient = (rectangleGradient * ((1.0 - roundWeight) * rounded * rounded)
                    + roundedGradient * (roundWeight * rectangle * rectangle)) / (denominator * denominator);
            } else {
                potential = 0.0;
                float a = length(rectangleGradient);
                float b = length(roundedGradient);
                gradient = a > 0.0 && b > 0.0 ? (rectangleGradient / a + roundedGradient / b)
                    * (0.5 * a * b / ((1.0 - roundWeight) * b + roundWeight * a)) : rectangleGradient;
            }
            float radial = sqrt(max(1.0 - potential, 0.0000001));
            return float3(span * (1.0 - radial), gradient * (span / (2.0 * radial)));
        }
        half4 main(float2 p) {
            if (foregroundOffset > 0.0 && (p.x >= outputSize.x || p.y >= outputSize.y)) return half4(0);
            float3 f = geometry(p);
            if (f.x < -1.0) return scene.eval(p);
            // Selection content stays spatially sharp. Brightness compression
            // affects only the backing, while the shared optical reflection still
            // describes the curved surface over opaque rows and artwork.
            half4 ink = foregroundOffset > 0.0 ? scene.eval(p + float2(0, foregroundOffset)) : half4(0);
            float u = clamp(1.0 - max(f.x, 0.0) / max(span, 0.001), 0.0, 1.0);
            float root = sqrt(max(1.0 - u * u, 0.0004));
            float crown = optics.x;
            float shoulder = optics.y;
            float height = crown * root * (1.0 + shoulder * u * u);
            float slope = crown / max(span, 0.001) * u / root * (1.0 - 2.0 * shoulder + 3.0 * shoulder * u * u);
            float3 normal = normalize(float3(-f.yz * slope, 1.0));
            half4 sampled = ink;
            if (ink.a < 1.0) {
                float3 ray = refract(float3(0, 0, -1), normal, 1.0 / 1.33);
                float2 exiting = ray.xy * 1.33;
                float exitZ = sqrt(max(1.0 - dot(exiting, exiting), 0.01));
                float2 displacement = (ray.xy * height / max(-ray.z, 0.12)
                    + exiting * optics.z / exitZ) * power;
                if (boundaryEnabled > 0.5) displacement = boundaryOffset(p) * power;
                float2 lookup = p + displacement;
                if (foregroundOffset > 0.0) {
                    lookup += backdropOffset;
                    sampled = lookup.x >= 0.0 && lookup.y >= 0.0 && lookup.x < backdropSize.x && lookup.y < backdropSize.y
                        ? scene.eval(lookup + float2(1)) : half4(0);
                } else sampled = scene.eval(lookup);
                float alpha = float(sampled.a);
                float3 color = float3(sampled.rgb) / max(alpha, 0.0001);
                color = glassColor(color, whiteTint, tone);
                sampled = ink + half4(half3(color * alpha), half(alpha)) * (1.0 - ink.a);
            }
            float alpha = float(sampled.a);
            float3 color = float3(sampled.rgb) / max(alpha, 0.0001);
            // A broad environment reflection belongs to the curved normal. No
            // painted plate, contour gradient or uniform adaptive-contrast coating.
            float facing = dot(normal, normalize(float3(-0.45, -0.75, 0.6)));
            float grazing = 1.0 - normal.z;
            grazing = grazing * grazing * grazing;
            float reflection = (0.018 * (1.0 - normal.z) + 0.26 * grazing) * illumination * power;
            float white = tone.z > 0.5 ? 1.0 - tone.x : 1.0;
            float black = tone.z > 0.5 ? 0.0 : tone.x;
            color = mix(color, float3(white), reflection * max(facing, 0.0));
            color = mix(color, float3(black), reflection * max(-facing, 0.0) * 0.3);
            // Environmental light still reflects when this recording is translucent
            // (for example an unpainted row over its parent's background).
            float reflected = reflection * max(facing, 0.0) * (1.0 - alpha);
            float reflectedTone = tone.z > 0.5 ? 1.0 - tone.x : 1.0;
            return half4(half3(color * alpha + reflected * reflectedTone), half(alpha + reflected));
        }
    """.trimIndent())
    private var last: List<Float> = emptyList()
    private var fieldBitmap: Bitmap? = null
    private var cached: RenderEffect? = null
    private var blurAmount = Float.NaN
    private var blurEffect: android.graphics.RenderEffect? = null
    init {
        shader.setInputBuffer("field", BitmapShader(emptyField, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }

    fun effect(geometry: LensGeometry, blur: Float, depth: Float, power: Float = 1f,
        whiteTint: Float = 0f, illumination: Float = 1f, map: LensDistanceMap? = null,
        origin: Offset = Offset.Zero, atlas: LensAtlas? = null, flattening: Float = 0f,
        tone: GlassTone = GlassTone(), boundaryProfile: BoundaryOptics? = null): RenderEffect {
        require(boundaryProfile == null || (map == null && illumination == 0f && power in 0f..1f)) {
            "Experimental boundary optics requires analytic geometry, no illumination, and bounded power"
        }
        val r = geometry.bounds
        val state = listOf(r.left, r.top, r.right, r.bottom, geometry.corner, geometry.span, blur,
            depth.coerceIn(.05f, 4f), power, whiteTint, illumination, origin.x, origin.y,
            map?.scale ?: 1f, map?.range ?: 1f, atlas?.foregroundY ?: 0f,
            atlas?.output?.width ?: 0f, atlas?.output?.height ?: 0f,
            atlas?.backdrop?.width ?: 0f, atlas?.backdrop?.height ?: 0f,
            atlas?.backdropOffset?.x ?: 0f, atlas?.backdropOffset?.y ?: 0f, flattening,
            tone.trim, tone.weight, if (tone.lightInk) 1f else 0f,
            boundaryProfile?.strength ?: 0f, boundaryProfile?.falloff ?: 1f,
            boundaryProfile?.coreStart ?: .3f, boundaryProfile?.coreEnd ?: .65f,
            boundaryProfile?.guard ?: .15f, boundaryProfile?.softness ?: .01f,
            if (boundaryProfile == null) 0f else 1f)
        if (state == last && fieldBitmap === map?.bitmap) return requireNotNull(cached)
        last = state
        shader.setFloatUniform("bounds", r.left, r.top, r.right, r.bottom)
        shader.setFloatUniform("corner", geometry.corner.coerceIn(0f, geometry.span))
        shader.setFloatUniform("span", geometry.span)
        // Surface-wide constants belong to the draw, not every shaded pixel.
        val d = state[7]
        val opticalDepth = d / kotlin.math.sqrt(1f + d * d / 4f)
        shader.setFloatUniform("optics", geometry.span * .62f * opticalDepth,
            .4f + .06f * flattening.coerceIn(0f, 1f), geometry.span * .32f * opticalDepth)
        shader.setFloatUniform("boundaryEnabled", if (boundaryProfile == null) 0f else 1f)
        shader.setFloatUniform("boundaryProfile", boundaryProfile?.strength ?: 0f, boundaryProfile?.falloff ?: 1f,
            boundaryProfile?.coreStart ?: .3f, boundaryProfile?.coreEnd ?: .65f)
        shader.setFloatUniform("boundaryGuard", boundaryProfile?.guard ?: .15f, boundaryProfile?.softness ?: .01f)
        shader.setFloatUniform("power", power)
        shader.setFloatUniform("whiteTint", whiteTint)
        shader.setFloatUniform("illumination", illumination)
        shader.setFloatUniform("tone", tone.trim, tone.weight, if (tone.lightInk) 1f else 0f)
        shader.setFloatUniform("foregroundOffset", atlas?.foregroundY ?: 0f)
        shader.setFloatUniform("outputSize", atlas?.output?.width ?: 0f, atlas?.output?.height ?: 0f)
        shader.setFloatUniform("backdropSize", atlas?.backdrop?.width ?: 0f, atlas?.backdrop?.height ?: 0f)
        shader.setFloatUniform("backdropOffset", atlas?.backdropOffset?.x ?: 0f, atlas?.backdropOffset?.y ?: 0f)
        shader.setFloatUniform("fieldMode", if (map == null) 0f else 1f)
        shader.setFloatUniform("fieldOrigin", origin.x + (map?.origin?.x ?: 0f), origin.y + (map?.origin?.y ?: 0f))
        shader.setFloatUniform("fieldScale", map?.scale ?: 1f)
        shader.setFloatUniform("fieldRange", map?.range ?: 1f)
        if (fieldBitmap !== map?.bitmap) {
            fieldBitmap = map?.bitmap
            shader.setInputBuffer("field", BitmapShader(map?.bitmap ?: emptyField, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            })
        }
        if (blur != blurAmount) {
            blurAmount = blur
            blurEffect = if (blur > 0f) android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
        }
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
        return (blurEffect?.let { android.graphics.RenderEffect.createChainEffect(lens, it) } ?: lens).asComposeRenderEffect().also { cached = it }
    }
    companion object {
        private val emptyField by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }
}
