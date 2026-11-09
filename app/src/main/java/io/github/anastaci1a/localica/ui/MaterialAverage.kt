package io.github.anastaci1a.localica.ui

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize

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

/** True when this scene is, or lies inside, a finished panel material (a frosted panel or
 * popup body). That material already carries its panel's tint and tone, so None does not
 * apply them a second time to what it samples there. Which pixels are sampled is decided
 * by the material's full bounds, never by this provenance. */
internal fun FrostedBackdrop.withinFinishedMaterial(): Boolean {
    var current: FrostedBackdrop? = this
    val visited = mutableSetOf<FrostedBackdrop>()
    while (current != null && visited.add(current)) {
        if (current.materialOnly) return true
        current = current.parent
    }
    return false
}

/** The colour mapping None applies to a mean sampled from [backing]. */
internal fun MaterialAverageStyle.forBacking(backing: FrostedBackdrop): MaterialAverageStyle =
    if (backing.withinFinishedMaterial()) MaterialAverageStyle(0f, GlassTone(), opaque) else this

/** An 8×8 GPU crop becomes one uniform color before scaling to the control.
 * No readback, full-control ray tracing, or full-panel averaging texture.
 * With [variableTaps] (the None material) the shader instead averages the texels [0, taps) of a level
 * of at most 8×8, so a reduction may stop there instead of at 1×1; the Normal 8×8 crop keeps its
 * original shader text unchanged. */
@RequiresApi(33)
internal class MaterialAverageRenderer(private val reduced: Boolean = false, private val uniformOpaque: Boolean = reduced,
    private val variableTaps: Boolean = false) {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float whiteTint;
        uniform float3 tone;
        uniform float coverage;
        uniform float opaque;
        ${if (variableTaps) "uniform float2 taps;" else ""}
        $GlassColorShader
        half4 main(float2 p) {
            float4 mean = float4(0);
            ${when {
                reduced -> "mean = float4(scene.eval(float2(0.5)));"
                variableTaps -> """
            // The exact mean of the whole level: texels [0, taps.x) by [0, taps.y), read once each at their centres.
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (float(x) < taps.x && float(y) < taps.y)
                        mean += float4(scene.eval(float2(float(x) + 0.5, float(y) + 0.5)));
                }
            }
            mean /= taps.x * taps.y;
            """
                else -> """
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) mean += float4(scene.eval(float2(float(x) + 0.5, float(y) + 0.5)));
            }
            mean *= 0.015625;
            """
            }}
            float alpha = opaque > 0.5 ? 1.0 : min(mean.a / coverage, 1.0);
            return half4(half3(glassColor(mean.rgb / max(mean.a, 0.0001), whiteTint, tone) * alpha), half(alpha));
        }
    """.trimIndent())
    private var previous: MaterialAverageStyle? = null
    private var cached: RenderEffect? = null
    private var previousCoverage = -1f
    private var previousTaps = IntSize.Zero
    fun effect(style: MaterialAverageStyle = MaterialAverageStyle(0f, GlassTone()), coverage: Float = 1f,
        taps: IntSize = IntSize(8, 8)): RenderEffect {
        require(variableTaps || taps == IntSize(8, 8)) { "Only a variable-taps renderer averages a $taps level" }
        require(taps.width in 1..8 && taps.height in 1..8) { "Mean taps $taps exceed the 8x8 shader block" }
        if (style == previous && coverage == previousCoverage && taps == previousTaps) return requireNotNull(cached)
        previous = style
        previousCoverage = coverage
        previousTaps = taps
        shader.setFloatUniform("whiteTint", style.whiteTint)
        shader.setFloatUniform("tone", style.tone.trim, style.tone.weight, style.tone.lightMix)
        shader.setFloatUniform("coverage", coverage)
        shader.setFloatUniform("opaque", if (uniformOpaque && style.opaque) 1f else 0f)
        if (variableTaps) shader.setFloatUniform("taps", taps.width.toFloat(), taps.height.toFloat())
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect().also { cached = it }
    }
}

/** One exact premultiplied mean per [block] of texels, compacted: output texel (i, j) holds the mean of
 * the source block at (i·block.x, j·block.y), so the destination clipped to source/block keeps every block
 * once. Every texel is read once at its centre, so nothing is resampled or skipped, and a 512 px side
 * needs three 8:1 steps instead of nine 2:1 halvings. Each block size compiles once. */
@RequiresApi(33)
internal object MaterialBlockMean {
    private val effects = HashMap<IntSize, RenderEffect>()
    fun effect(block: IntSize): RenderEffect = effects.getOrPut(block) {
        require(block.width in 1..8 && block.height in 1..8) { "Block $block exceeds the 8x8 shader block" }
        val shader = RuntimeShader("""
            uniform shader scene;
            half4 main(float2 p) {
                float2 block = float2(${block.width}.0, ${block.height}.0);
                float2 origin = floor(p) * block;
                float4 sum = float4(0);
                for (int y = 0; y < ${block.height}; y++) {
                    for (int x = 0; x < ${block.width}; x++) sum += float4(scene.eval(origin + float2(float(x) + 0.5, float(y) + 0.5)));
                }
                return half4(sum * ${1.0 / (block.width * block.height)});
            }
        """.trimIndent())
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
    }
}
