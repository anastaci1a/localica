package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.ceil

/** Geometry-only distance ramp. Never reads pixels back from the recorded screen. */
@RequiresApi(33)
internal class ConnectedLensOptics {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform shader field;
        uniform float2 fieldOrigin;
        uniform float band;
        uniform float strength;
        float depth(float2 p) { return float(field.eval(p - fieldOrigin).r); }
        half4 main(float2 p) {
            half4 base = scene.eval(p);
            half4 mask = field.eval(p - fieldOrigin);
            if (mask.a < 0.001) return base;
            float d = depth(p);
            float2 gradient = float2(depth(p + float2(3,0)) - depth(p - float2(3,0)),
                depth(p + float2(0,3)) - depth(p - float2(0,3)));
            float2 normal = -gradient / max(length(gradient), 0.0001);
            float t = 1.0 - smoothstep(0.0, 1.0, d);
            float slope = t * t / sqrt(max(1.05 - t * t, 0.12));
            float bend = min(band * 0.30, slope * band * 0.18) * strength;
            // Keep horizontal text baselines readable while the side walls act as lenses.
            bend *= 0.35 + 0.65 * abs(normal.x);
            half4 glass = scene.eval(p - normal * bend);
            float light = dot(normal, normalize(float2(-0.6,-1.0)));
            // Broad grazing illumination makes curvature visible even over a plain album
            // background; the flat center gets neither tint nor distortion.
            float rim = (0.18 * t * t + 0.4 * pow(t, 10.0)) * strength;
            glass.rgb = mix(glass.rgb, half3(glass.a), half(max(light,0.0) * rim));
            glass.rgb *= half(1.0 - max(-light,0.0) * t * t * 0.13 * strength);
            return mix(base, glass, mask.a * half(strength));
        }
    """.trimIndent())
    private var previous = emptyList<SelectionRegion>()
    private var previousSize = Size.Zero
    private var previousBand = 0f
    private var cached: RenderEffect? = null

    fun effect(regions: List<SelectionRegion>, path: Path, size: Size, band: Float, strength: Float): RenderEffect? {
        if (regions == previous && size == previousSize && band == previousBand) return cached
        previous = regions; previousSize = size; previousBand = band
        val bounds = path.getBounds().intersect(Rect(0f, 0f, size.width, size.height))
        if (bounds.isEmpty) { cached = null; return null }
        val left = floor(bounds.left) - 2f
        val top = floor(bounds.top) - 2f
        val bitmap = Bitmap.createBitmap(ceil(bounds.right - left + 2).toInt().coerceAtLeast(1),
            ceil(bounds.bottom - top + 2).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-left, -top)
        val outline = path.asAndroidPath()
        canvas.clipPath(outline)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        // The red channel is inward distance / band; alpha is silhouette coverage.
        // Only geometry is rasterized; refraction of the live content stays on the GPU.
        for (step in 64 downTo 1) {
            val distance = step / 64f
            val level = ((step - 1) * 255 / 64)
            paint.color = android.graphics.Color.rgb(level, level, level)
            paint.strokeWidth = 2 * band * distance
            canvas.drawPath(outline, paint)
        }
        shader.setInputShader("field", BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        })
        shader.setFloatUniform("fieldOrigin", left, top)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("strength", strength)
        cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        return cached
    }
}
