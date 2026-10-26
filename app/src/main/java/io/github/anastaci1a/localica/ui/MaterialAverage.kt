package io.github.anastaci1a.localica.ui

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.runtime.staticCompositionLocalOf

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

internal data class MaterialAverageStyle(val whiteTint: Float, val tone: GlassTone)

/** Sampling-route evidence only; a Canvas choice does not certify presented pixels. */
internal data class MaterialAverageSamplingFrame(val legacy: Boolean, val sourceIdentity: Int,
    val publication: Int?, val validSamples: Int, val weight: Float)
internal val LocalMaterialAverageSamplingObserver = staticCompositionLocalOf<((MaterialAverageSamplingFrame) -> Unit)?> { null }

/** Bit order matches the shared row-major 8x8 source-recording plan. */
internal val MaterialAverageCoverage.validMask: Long
    get() = samples.foldIndexed(0L) { index, mask, sample ->
        if (sample.valid) mask or (1L shl index) else mask
    }

/** An 8×8 GPU crop becomes one uniform color before scaling to the control.
 * No readback, full-control ray tracing, or full-panel averaging texture. */
@RequiresApi(33)
internal class MaterialAverageRenderer {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float whiteTint;
        uniform float3 tone;
        uniform float coverage[64];
        uniform float coverageCount;
        $GlassColorShader
        half4 main(float2 p) {
            float4 mean = float4(0);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) mean += float4(scene.eval(float2(float(x) + 0.5, float(y) + 0.5))) * coverage[y * 8 + x];
            }
            mean /= coverageCount;
            return half4(half3(glassColor(mean.rgb / max(mean.a, 0.0001), whiteTint, tone) * mean.a), half(mean.a));
        }
    """.trimIndent())
    private var previous: MaterialAverageStyle? = null
    private var previousMask = 0L
    private var cached: RenderEffect? = null
    fun effect(style: MaterialAverageStyle = MaterialAverageStyle(0f, GlassTone()), validMask: Long = -1L): RenderEffect {
        require(validMask != 0L) { "An average requires current valid material samples" }
        if (style == previous && validMask == previousMask) return requireNotNull(cached)
        previous = style
        previousMask = validMask
        shader.setFloatUniform("whiteTint", style.whiteTint)
        shader.setFloatUniform("tone", style.tone.trim, style.tone.weight, if (style.tone.lightInk) 1f else 0f)
        shader.setFloatUniform("coverage", *FloatArray(64) { index -> if (validMask and (1L shl index) != 0L) 1f else 0f })
        shader.setFloatUniform("coverageCount", java.lang.Long.bitCount(validMask).toFloat())
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect().also { cached = it }
    }
}
