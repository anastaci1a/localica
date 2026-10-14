package io.github.anastaci1a.localica.ui

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Eligibility is provenance plus coverage, never guessed from a few similar pixels.
 * Foreground-containing recordings (lists, artwork and modal contents) stay optical. */
internal fun FrostedBackdrop.canAverage(origin: Offset, extent: Size): Boolean {
    if (!materialOnly || !recorded || extent.width <= 0f || extent.height <= 0f) return false
    val local = origin - this.origin
    // Conservatively exclude the rounded corners of the panel's recording.
    val inset = materialCorner.coerceAtMost(minOf(size.width, size.height) / 2f)
    fun inside(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x > size.width || y > size.height) return false
        val dx = (inset - x).coerceAtLeast(0f) + (x - (size.width - inset)).coerceAtLeast(0f)
        val dy = (inset - y).coerceAtLeast(0f) + (y - (size.height - inset)).coerceAtLeast(0f)
        return dx * dx + dy * dy <= inset * inset
    }
    return inside(local.x, local.y) && inside(local.x + extent.width, local.y) &&
        inside(local.x, local.y + extent.height) && inside(local.x + extent.width, local.y + extent.height)
}

internal data class MaterialAverageStyle(val whiteTint: Float, val tone: GlassTone)

/** An 8×8 GPU crop becomes one uniform color before scaling to the control.
 * No readback, full-control ray tracing, or full-panel averaging texture. */
@RequiresApi(33)
internal class MaterialAverageRenderer {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float whiteTint;
        uniform float3 tone;
        $GlassColorShader
        half4 main(float2 p) {
            float4 mean = float4(0);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) mean += float4(scene.eval(float2(float(x) + 0.5, float(y) + 0.5)));
            }
            mean *= 0.015625;
            return half4(half3(glassColor(mean.rgb / max(mean.a, 0.0001), whiteTint, tone) * mean.a), half(mean.a));
        }
    """.trimIndent())
    private var previous: MaterialAverageStyle? = null
    private var cached: RenderEffect? = null
    fun effect(style: MaterialAverageStyle = MaterialAverageStyle(0f, GlassTone())): RenderEffect {
        if (style == previous) return requireNotNull(cached)
        previous = style
        shader.setFloatUniform("whiteTint", style.whiteTint)
        shader.setFloatUniform("tone", style.tone.trim, style.tone.weight, if (style.tone.lightInk) 1f else 0f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect().also { cached = it }
    }
}
