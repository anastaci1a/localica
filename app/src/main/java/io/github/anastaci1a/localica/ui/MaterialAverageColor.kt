package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.isSpecified

/**
 * Blend trusted, bounded sRGB raster means in premultiplied form, then apply GlassColorShader's
 * white-tint/tone mapping once. Contributor provenance belongs to the caller. Weights need not sum
 * to one, but their sum must be representable and resulting alpha must remain in [0, 1].
 * Every input is validated, including zero-weight colors; zero weights contribute no arithmetic.
 */
internal fun materialAverageColor(
    contributors: List<Pair<Color, Float>>,
    style: MaterialAverageStyle,
): Color {
    require(style.whiteTint.isFinite() && style.whiteTint in 0f..1f &&
        style.tone.trim.isFinite() && style.tone.trim in 0f.. .5f &&
        style.tone.weight.isFinite() && style.tone.weight in 1f..2f) { "Invalid material average style" }
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f; var totalWeight = 0f
    for ((color, weight) in contributors) {
        require(weight.isFinite() && weight >= 0f) { "Invalid material contributor weight" }
        require(color.isSpecified && color.colorSpace == ColorSpaces.Srgb &&
            listOf(color.red, color.green, color.blue, color.alpha).all { it.isFinite() && it in 0f..1f }) {
            "Material contributor must be a specified bounded sRGB color"
        }
        if (weight == 0f) continue
        totalWeight += weight
        val weightedAlpha = color.alpha * weight
        alpha += weightedAlpha
        red += color.red * weightedAlpha
        green += color.green * weightedAlpha
        blue += color.blue * weightedAlpha
        require(listOf(totalWeight, alpha, red, green, blue).all { it.isFinite() } && alpha <= 1f) {
            "Material contributor accumulation overflow"
        }
    }
    if (alpha == 0f) return Color.Transparent
    // Keep the shader's low-alpha denominator and constants, not a different tint approximation.
    val denominator = maxOf(alpha, .0001f)
    red /= denominator; green /= denominator; blue /= denominator
    val luma = (.2126f * red + .7152f * green + .0722f * blue).coerceIn(0f, 1f)
    val white = style.whiteTint
    val lifted = 1f - (1f - luma) * (1f - white) / maxOf(1f - white * luma, .0001f)
    val tint = ((lifted - luma) / maxOf(1f - luma, .0001f)).coerceIn(0f, 1f)
    fun mapped(channel: Float) = style.tone.channel((channel + (1f - channel) * tint).coerceIn(0f, 1f))
    return Color(mapped(red), mapped(green), mapped(blue), alpha)
}
