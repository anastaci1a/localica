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

/** Compact input: a complete backdrop followed by only the visible foreground.
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
        uniform float depth;
        uniform float power;
        uniform float whiteTint;
        uniform float illumination;
        uniform float fieldMode;
        uniform float2 fieldOrigin;
        uniform float fieldScale;
        uniform float fieldRange;
        uniform float foregroundOffset;
        uniform float2 outputSize;
        uniform float2 backdropSize;
        uniform float2 backdropOffset;
        float3 geometry(float2 p) {
            if (fieldMode > 0.5) {
                float3 f = float3(field.eval((p - fieldOrigin) * fieldScale).rgb);
                return float3(f.r * fieldRange, f.gb * 2.0 - 1.0);
            }
            float2 middle = (bounds.xy + bounds.zw) * 0.5;
            float2 halfSize = (bounds.zw - bounds.xy) * 0.5;
            float2 local = p - middle;
            float2 q = abs(local);
            if (dot(q, q) < 0.0000001) return float3(span, 0, 0);
            float2 core = halfSize - corner;
            float t;
            float2 gradient;
            if (q.y * halfSize.x <= q.x * core.y) {
                t = q.x / halfSize.x; gradient = float2(1.0 / halfSize.x, 0);
            } else if (q.x * halfSize.y <= q.y * core.x) {
                t = q.y / halfSize.y; gradient = float2(0, 1.0 / halfSize.y);
            } else {
                float b = dot(q, core);
                float c = dot(q, q);
                float a = dot(core, core) - corner * corner;
                t = c / max(b + sqrt(max(b * b - a * c, 0.0)), 0.000001);
                float2 normal = q - core * t;
                gradient = normal / max(dot(normal, core) + t * corner * corner, 0.000001);
            }
            return float3(span * (1.0 - t), -sign(local) * gradient * span);
        }
        half4 main(float2 p) {
            if (foregroundOffset > 0.0 && (p.x >= outputSize.x || p.y >= outputSize.y)) return half4(0);
            float3 f = geometry(p);
            if (f.x < -1.0) return scene.eval(p);
            float u = clamp(1.0 - max(f.x, 0.0) / max(span, 0.001), 0.0, 1.0);
            float root = sqrt(max(1.0 - u * u, 0.0004));
            float opticalDepth = depth / sqrt(1.0 + depth * depth / 4.0);
            float crown = span * 0.62 * opticalDepth;
            float height = crown * root * (1.0 + 0.4 * u * u);
            float slope = crown / max(span, 0.001) * u / root * (0.2 + 1.2 * u * u);
            float3 normal = normalize(float3(-f.yz * slope, 1.0));
            // A selected row is foreground on the lens, like a button's label.
            // Its atlas entry stays sharp while the separate backing refracts.
            half4 ink = foregroundOffset > 0.0 ? scene.eval(p + float2(0, foregroundOffset)) : half4(0);
            half4 sampled = ink;
            if (ink.a < 1.0) {
                float3 ray = refract(float3(0, 0, -1), normal, 1.0 / 1.33);
                float2 exiting = ray.xy * 1.33;
                float exitZ = sqrt(max(1.0 - dot(exiting, exiting), 0.01));
                float2 displacement = (ray.xy * height / max(-ray.z, 0.12)
                    + exiting * span * 0.32 * opticalDepth / exitZ) * power;
                float2 lookup = p + displacement;
                if (foregroundOffset > 0.0) {
                    lookup += backdropOffset;
                    sampled = lookup.x >= 0.0 && lookup.y >= 0.0 && lookup.x < backdropSize.x && lookup.y < backdropSize.y
                        ? scene.eval(lookup + float2(1)) : half4(0);
                } else sampled = scene.eval(lookup);
                sampled = ink + sampled * (1.0 - ink.a);
            }
            float alpha = float(sampled.a);
            float3 color = float3(sampled.rgb) / max(alpha, 0.0001);
            // User-selected white tint is separate from readability. Its ordered
            // shoulder lifts shadows more than highlights, retaining saturated light.
            float luma = clamp(dot(color, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            float lifted = 1.0 - (1.0 - luma) * (1.0 - whiteTint) / max(1.0 - whiteTint * luma, 0.0001);
            float tint = clamp((lifted - luma) / max(1.0 - luma, 0.0001), 0.0, 1.0);
            color = mix(color, float3(1), tint);
            // A broad environment reflection belongs to the curved normal. No
            // painted plate, contour gradient or uniform adaptive-contrast coating.
            float facing = dot(normal, normalize(float3(-0.45, -0.75, 0.6)));
            float grazing = pow(1.0 - normal.z, 3.0);
            float reflection = (0.018 * (1.0 - normal.z) + 0.26 * grazing) * illumination * power;
            color = mix(color, float3(1), reflection * max(facing, 0.0));
            color *= 1.0 - reflection * max(-facing, 0.0) * 0.3;
            // Environmental light still reflects when this recording is translucent
            // (for example an unpainted row over its parent's background).
            float reflected = reflection * max(facing, 0.0) * (1.0 - alpha);
            return half4(half3(color * alpha + reflected), half(alpha + reflected));
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
        origin: Offset = Offset.Zero, atlas: LensAtlas? = null): RenderEffect {
        val r = geometry.bounds
        val state = listOf(r.left, r.top, r.right, r.bottom, geometry.corner, geometry.span, blur,
            depth.coerceIn(.05f, 4f), power, whiteTint, illumination, origin.x, origin.y,
            map?.scale ?: 1f, map?.range ?: 1f, atlas?.foregroundY ?: 0f,
            atlas?.output?.width ?: 0f, atlas?.output?.height ?: 0f,
            atlas?.backdrop?.width ?: 0f, atlas?.backdrop?.height ?: 0f,
            atlas?.backdropOffset?.x ?: 0f, atlas?.backdropOffset?.y ?: 0f)
        if (state == last && fieldBitmap === map?.bitmap) return requireNotNull(cached)
        last = state
        shader.setFloatUniform("bounds", r.left, r.top, r.right, r.bottom)
        shader.setFloatUniform("corner", geometry.corner.coerceIn(0f, geometry.span))
        shader.setFloatUniform("span", geometry.span)
        shader.setFloatUniform("depth", state[7])
        shader.setFloatUniform("power", power)
        shader.setFloatUniform("whiteTint", whiteTint)
        shader.setFloatUniform("illumination", illumination)
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
