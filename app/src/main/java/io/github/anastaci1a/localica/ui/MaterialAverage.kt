package io.github.anastaci1a.localica.ui

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Eligibility is provenance plus coverage, never guessed from a few similar pixels.
 * Foreground-containing recordings (lists, artwork and modal contents) stay optical. */
internal fun FrostedBackdrop.canAverage(origin: Offset, extent: Size): Boolean {
    if (extent.width <= 0f || extent.height <= 0f) return false
    val toLocal = location.registrationToScreen()?.inverse() ?: return false
    return canAverage(toLocal.corners(Rect(origin, extent)))
}

/** All footprint corners are in the recording's local pixels, including scale and shear. */
internal fun FrostedBackdrop.canAverage(localCorners: List<Offset>): Boolean {
    return materialOnly && recorded && backdropContainsFootprint(
        size.width.toFloat(), size.height.toFloat(), materialCorner, localCorners)
}

internal data class MaterialAverageStyle(val whiteTint: Float, val tone: GlassTone, val opaque: Boolean = false)

/** None controls inherit the finished material, excluding intervening foreground recordings. */
internal fun FrostedBackdrop.noneMaterialSource(): FrostedBackdrop {
    var current: FrostedBackdrop? = this
    val visited = mutableSetOf<FrostedBackdrop>()
    while (current != null && visited.add(current)) {
        if (current.materialOnly) return current
        current = current.parent
    }
    return this
}

/** An 8×8 GPU crop becomes one uniform color before scaling to the control.
 * No readback, full-control ray tracing, or full-panel averaging texture. */
@RequiresApi(33)
internal class MaterialAverageRenderer(private val reduced: Boolean = false) {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float whiteTint;
        uniform float3 tone;
        uniform float coverage;
        uniform float opaque;
        $GlassColorShader
        half4 main(float2 p) {
            float4 mean = float4(0);
            ${if (reduced) "mean = float4(scene.eval(float2(0.5)));" else """
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) mean += float4(scene.eval(float2(float(x) + 0.5, float(y) + 0.5)));
            }
            mean *= 0.015625;
            """}
            float alpha = opaque > 0.5 ? 1.0 : min(mean.a / coverage, 1.0);
            return half4(half3(glassColor(mean.rgb / max(mean.a, 0.0001), whiteTint, tone) * alpha), half(alpha));
        }
    """.trimIndent())
    private var previous: MaterialAverageStyle? = null
    private var cached: RenderEffect? = null
    private var previousCoverage = -1f
    fun effect(style: MaterialAverageStyle = MaterialAverageStyle(0f, GlassTone()), coverage: Float = 1f): RenderEffect {
        if (style == previous && coverage == previousCoverage) return requireNotNull(cached)
        previous = style
        previousCoverage = coverage
        shader.setFloatUniform("whiteTint", style.whiteTint)
        shader.setFloatUniform("tone", style.tone.trim, style.tone.weight, style.tone.lightMix)
        shader.setFloatUniform("coverage", coverage)
        shader.setFloatUniform("opaque", if (reduced && style.opaque) 1f else 0f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect().also { cached = it }
    }
}
