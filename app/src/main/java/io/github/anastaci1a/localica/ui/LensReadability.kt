package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Contrast lives at the ink, not as an opaque wash over every backdrop pixel.
 * Record foreground once; only its opposing soft silhouette supplies local support. */
@Composable internal fun Modifier.lensInkSupport(ink: Color, strength: Float): Modifier {
    val foreground = rememberGraphicsLayer()
    val support = rememberGraphicsLayer()
    val opposite = if (ink.luminance() > .5f) Color.Black else Color.White
    val filter = remember(opposite) { ColorFilter.tint(opposite, BlendMode.SrcIn) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur = with(density) { .85.dp.toPx() }
    val spread = with(density) { 1.dp.toPx() }
    val effect = remember(blur, spread) {
        if (Build.VERSION.SDK_INT >= 33) {
            val mask = android.graphics.RuntimeShader("""
                uniform shader ink;
                uniform float spread;
                half4 main(float2 p) {
                    half a = ink.eval(p).a;
                    a = max(a, ink.eval(p + float2(spread, 0)).a);
                    a = max(a, ink.eval(p - float2(spread, 0)).a);
                    a = max(a, ink.eval(p + float2(0, spread)).a);
                    a = max(a, ink.eval(p - float2(0, spread)).a);
                    return half4(a);
                }
            """.trimIndent()).apply { setFloatUniform("spread", spread) }
            android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.DECAL),
                android.graphics.RenderEffect.createRuntimeShaderEffect(mask, "ink")).asComposeRenderEffect()
        } else if (Build.VERSION.SDK_INT >= 31) BlurEffect(blur, blur, TileMode.Decal) else null
    }
    return drawWithContent {
        if (strength <= 0f) { drawContent(); return@drawWithContent }
        foreground.record { this@drawWithContent.drawContent() }
        val padding = kotlin.math.ceil(blur * 3f + spread).toInt()
        support.colorFilter = filter
        support.alpha = strength * .9f
        support.renderEffect = effect
        support.record(Density(this.density, fontScale), layoutDirection,
            IntSize(size.width.toInt() + padding * 2, size.height.toInt() + padding * 2)) {
            translate(padding.toFloat(), padding.toFloat()) {
                if (Build.VERSION.SDK_INT >= 33) drawLayer(foreground)
                else {
                    drawLayer(foreground)
                    translate(-spread, 0f) { drawLayer(foreground) }
                    translate(spread, 0f) { drawLayer(foreground) }
                    translate(0f, -spread) { drawLayer(foreground) }
                    translate(0f, spread) { drawLayer(foreground) }
                }
            }
        }
        translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(support) }
        drawLayer(foreground)
    }
}
