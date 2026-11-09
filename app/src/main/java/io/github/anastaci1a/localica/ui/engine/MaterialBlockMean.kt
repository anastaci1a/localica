package io.github.anastaci1a.localica.ui.engine

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize

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
