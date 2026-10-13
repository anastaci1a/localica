package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
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
    private val shader = android.graphics.RuntimeShader(WaterCapOptics + """
        uniform shader scene;
        uniform shader field;
        uniform float2 fieldOrigin;
        uniform float rasterScale;
        uniform float band;
        uniform float strength;
        uniform float thickness;
        half4 main(float2 p) {
            half4 base = scene.eval(p);
            half4 mask = field.eval((p - fieldOrigin) * rasterScale);
            if (mask.a < 0.001) return base;
            float3 geometry = float3(mask.rgb / mask.a);
            float d = geometry.r;
            float2 gradient = geometry.gb * 2.0 - 1.0;
            float t = clamp(1.0 - d, 0.0, 1.0);
            float root = sqrt(max(1.0 - t * t, 0.0004));
            float cap = band * thickness * 0.55;
            float3 normal = normalize(float3(-gradient * thickness * 0.55 * t / root, 1.0));
            float2 bend = capDisplacement(normal, cap * root) * strength;
            // Keep horizontal text baselines readable while the side walls act as lenses.
            bend.y *= 0.35;
            half4 glass = scene.eval(p + bend);
            float light = dot(normal, normalize(float3(-0.55,-1.0,1.2)));
            // A broad environment reflection follows the curved normal; it has no
            // inset plate or sudden boundary and remains visible over uniform color.
            float rim = (capFresnel(normal) * 0.32 + (1.0 - normal.z) * 0.12) * strength;
            glass.rgb = mix(glass.rgb, half3(glass.a), half(max(light,0.0) * rim));
            glass.rgb *= half(1.0 - max(-light,0.0) * rim * 0.35);
            return mix(base, glass, mask.a * half(strength));
        }
    """.trimIndent())
    private var previous = emptyList<SelectionRegion>()
    private var previousBand = 0f
    private var cached: RenderEffect? = null
    private var fieldOrigin = Offset.Zero
    private var previousOrigin = Offset(Float.NaN, Float.NaN)
    private var previousStrength = Float.NaN
    private var previousThickness = Float.NaN
    internal var rasterizations = 0
        private set

    fun effect(regions: List<SelectionRegion>, path: Path, band: Float, strength: Float, origin: Offset = Offset.Zero, thickness: Float = 1.35f): RenderEffect? {
        val opticsChanged = strength != previousStrength || thickness != previousThickness
        previousStrength = strength; previousThickness = thickness
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("thickness", thickness)
        if (regions == previous && band == previousBand) {
            if (origin != previousOrigin || opticsChanged) {
                previousOrigin = origin
                shader.setFloatUniform("fieldOrigin", fieldOrigin.x + origin.x, fieldOrigin.y + origin.y)
                cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
            }
            return cached
        }
        previous = regions; previousBand = band
        val bounds = path.getBounds()
        if (bounds.isEmpty) { cached = null; return null }
        val left = floor(bounds.left) - 2f
        val top = floor(bounds.top) - 2f
        val scale = .5f
        rasterizations++
        val bitmap = Bitmap.createBitmap(ceil((bounds.right - left + 2) * scale).toInt().coerceAtLeast(1),
            ceil((bounds.bottom - top + 2) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        val outline = path.asAndroidPath()
        canvas.clipPath(outline)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        canvas.drawPath(outline, paint)
        // Encode accurate inward distance and its smooth gradient, avoiding a
        // derivative of the old quantized stroke ramp (visible stair-step lensing).
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val distances = selectionDistances(pixels, bitmap.width, bitmap.height)
        fun distance(x: Int, y: Int) = distances[y.coerceIn(0, bitmap.height - 1) * bitmap.width + x.coerceIn(0, bitmap.width - 1)]
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val index = y * bitmap.width + x
            val alpha = pixels[index] ushr 24
            val d = ((distances[index] - .5f) / (band * scale)).coerceIn(0f, 1f)
            val gx = ((distance(x + 2, y) - distance(x - 2, y)) / 4f).coerceIn(-1f, 1f)
            val gy = ((distance(x, y + 2) - distance(x, y - 2)) / 4f).coerceIn(-1f, 1f)
            pixels[index] = android.graphics.Color.argb(alpha, (d * 255).toInt(),
                ((gx + 1f) * 127.5f).toInt(), ((gy + 1f) * 127.5f).toInt())
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        shader.setInputShader("field", BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        })
        fieldOrigin = Offset(left, top)
        previousOrigin = origin
        shader.setFloatUniform("fieldOrigin", left + origin.x, top + origin.y)
        shader.setFloatUniform("rasterScale", scale)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("strength", strength)
        cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        return cached
    }
}
