package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/** Single-draw backdrop alpha candidate. Native rounding drift is gated by the pixel fixtures. */
@RequiresApi(33)
internal class PlayerBackdropAlphaShader {
    private val shader = RuntimeShader("""
        uniform shader base;
        uniform shader radial;
        uniform shader wash;
        uniform shader softWash;
        uniform shader grain;
        uniform float washAlpha;
        uniform float softAlpha;
        uniform float grainAlpha;
        float4 byteWrite(float4 c) { return floor(clamp(c, 0.0, 1.0) * 255.0 + 0.5) / 255.0; }
        float4 sourceOver(float4 s, float4 d) {
            s = byteWrite(s);
            return byteWrite(s + d * (1.0 - s.a));
        }
        half4 main(float2 p) {
            float4 c = byteWrite(float4(base.eval(p)));
            c = sourceOver(float4(radial.eval(p)), c);
            if (washAlpha > 0.0) c = sourceOver(float4(wash.eval(p)) * washAlpha, c);
            if (softAlpha > 0.0) c = sourceOver(float4(softWash.eval(p)) * softAlpha, c);
            c = sourceOver(float4(grain.eval(p)) * grainAlpha, c);
            return half4(c);
        }
    """.trimIndent())
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@PlayerBackdropAlphaShader.shader }
    private val transparent = android.graphics.LinearGradient(0f, 0f, 1f, 1f, 0, 0, Shader.TileMode.CLAMP)
    private val samplers = HashMap<Bitmap, BitmapShader>()
    private val washMatrix = Matrix()
    private val softMatrix = Matrix()
    private val grainMatrix = Matrix()
    private val grainSampler = BitmapShader(playerGrain, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    }
    private var gradientKey: List<Any>? = null
    private fun sampler(bitmap: Bitmap): BitmapShader = samplers.getOrPut(bitmap) {
        if (samplers.size >= 4) samplers.clear()
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // RuntimeShader inputs otherwise default to nearest, irrespective of Paint flags.
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    fun draw(scope: DrawScope, palette: PlayerPalette, tone: GlassTone, wash: Bitmap?, soft: Bitmap?,
        fullArtBlend: Float, artTop: Float, artHeight: Float, artLeft: Float, artWidth: Float,
        softness: Float, opacity: Float) = with(scope) {
        val key = listOf(size, palette.top, palette.middle, palette.bottom, tone)
        if (gradientKey != key) {
            shader.setInputShader("base", (Brush.verticalGradient(listOf(tone.color(palette.top),
                tone.color(palette.middle), tone.color(palette.bottom))) as ShaderBrush).createShader(size))
            shader.setInputShader("radial", (Brush.radialGradient(listOf(tone.color(palette.top).copy(alpha = .45f),
                Color.Transparent), center = Offset(size.width * .1f, size.height * .4f),
                radius = size.width * 1.2f) as ShaderBrush).createShader(size))
            gradientKey = key
        }
        val hasWash = wash != null && fullArtBlend > 0f && artHeight > 0f
        val washShader = if (hasWash) sampler(requireNotNull(wash)).also {
            washMatrix.setScale((artWidth.takeIf { it > 0f } ?: size.width) / ArtworkWash.WIDTH,
                artHeight / ArtworkWash.WIDTH)
            washMatrix.postTranslate(artLeft, artTop + artHeight * ArtworkWash.TOP)
            it.setLocalMatrix(washMatrix)
        } else transparent
        shader.setInputShader("wash", washShader)
        val softShader = if (hasWash && soft != null && softness > 0f) sampler(soft).also {
            softMatrix.set(washMatrix)
            softMatrix.preScale(ArtworkWash.WIDTH.toFloat() / soft.width, ArtworkWash.HEIGHT.toFloat() / soft.height)
            it.setLocalMatrix(softMatrix)
        } else transparent
        val grainShader = grainSampler.also {
            grainMatrix.setScale(size.width / 512f, size.height / 1024f)
            it.setLocalMatrix(grainMatrix)
        }
        shader.setInputShader("softWash", softShader)
        shader.setInputShader("grain", grainShader)
        shader.setFloatUniform("washAlpha", if (hasWash) (255 * fullArtBlend).toInt().coerceIn(0, 255) / 255f else 0f)
        shader.setFloatUniform("softAlpha", if (hasWash && soft != null && softness > 0f)
            (255 * fullArtBlend * softness).toInt().coerceIn(0, 255) / 255f else 0f)
        shader.setFloatUniform("grainAlpha", (255 * (1f - fullArtBlend * .8f)).toInt() / 255f)
        // HWUI's layerNeedsPaint converts the RenderNode alpha to an unsigned byte.
        paint.alpha = (255 * opacity).toInt().coerceIn(0, 255)
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}
