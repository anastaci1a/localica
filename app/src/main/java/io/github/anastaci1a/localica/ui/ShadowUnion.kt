package io.github.anastaci1a.localica.ui

import kotlin.math.pow

/** Existing outer-to-inner ring opacity, shared by uniform and weighted unions. */
internal fun shadowRingAlpha(index: Int, strength: Float, opacity: Float): Float =
    strength * opacity * .07f * (1f - (16 - index) / 16f).pow(2)

/** A level set of the maximum individual shadow fields. -1 omits that body. */
internal data class ShadowBand(val alpha: Float, val ringIndices: List<Int>)

internal fun weightedShadowBands(opacities: List<Float>, strength: Float): List<ShadowBand> {
    if (strength <= 0f || opacities.isEmpty()) return emptyList()
    val fields = opacities.map { opacity ->
        var accumulated = 0.0
        DoubleArray(16) { index ->
            val layer = shadowRingAlpha(index, strength, opacity.coerceIn(0f, 1f)).toDouble()
            accumulated += (1.0 - accumulated) * layer
            accumulated
        }
    }
    val levels = fields.flatMap { it.asList() }.filter { it > 0.0 }.distinct().sorted()
    var previous = 0.0
    return levels.map { level ->
        val band = ShadowBand(((level - previous) / (1.0 - previous)).toFloat(),
            fields.map { field -> field.indexOfFirst { it >= level } })
        previous = level
        band
    }
}
