package io.github.anastaci1a.localica.ui.engine.diagnostics.fixture

import kotlin.math.*

internal data class ShadowMass(val above: Double, val below: Double) {
    val ratio get() = below / above
}

/** Pixel centres in equal-height bands outside the presented body's top/bottom. */
internal fun shadowMass(width: Int, height: Int, top: Float, bottom: Float,
    maxRows: Int = Int.MAX_VALUE, signal: (Int, Int) -> Double): ShadowMass {
    val upper = floor(top).toInt()
    val lower = ceil(bottom).toInt()
    val rows = min(maxRows, min(upper, height - lower))
    require(rows > 0)
    var above = 0.0; var below = 0.0
    for (dy in 0 until rows) for (x in 0 until width) {
        above += signal(x, upper - 1 - dy)
        below += signal(x, lower + dy)
    }
    return ShadowMass(above, below)
}

internal data class ShadowMassReference(val expected: ShadowMass, val minimum: Double, val maximum: Double) {
    fun accepts(actual: ShadowMass) = actual.above > 0 && actual.below > actual.above &&
        actual.ratio in minimum..maximum
}

internal data class ShadowMassContour(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

/** Independent distance-field integral; one 8-bit code bounds sampling/coverage quantization. */
internal fun shadowMassReference(width: Int, height: Int, left: Float, top: Float, right: Float,
    bottom: Float, radius: Float, density: Float, spread: Float = 1f, shiftDp: Float = 8f,
    code: Double = 1.0 / 255, bandDp: Float = Float.POSITIVE_INFINITY,
    contours: List<ShadowMassContour> = listOf(ShadowMassContour(left, top, right, bottom, radius)),
    scaleX: Float = 1f, scaleY: Float = 1f): ShadowMassReference {
    fun alpha(x: Int, y: Int): Double {
        val distance = contours.minOf { c ->
            val qx = abs(x + .5 - (c.left + c.right) / 2) / scaleX - ((c.right - c.left) / (2 * scaleX) - c.radius)
            val qy = abs((y + .5 - (c.top + c.bottom) / 2) / scaleY - shiftDp * density) - ((c.bottom - c.top) / (2 * scaleY) - c.radius)
            hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - c.radius
        }
        val v = (distance / (24 * density * spread)).coerceIn(-2.0, 2.0)
        val q = v * v
        return (.1 * (((.0029544830322265625 * q - .034454345703125) * q +
            .168212890625) * q - .560546875) * v + .05).coerceIn(0.0, .1)
    }
    val rows = (bandDp * density).toInt()
    val expected = shadowMass(width, height, top, bottom, rows, ::alpha)
    val low = shadowMass(width, height, top, bottom, rows) { x, y -> max(0.0, alpha(x, y) - code) }
    val high = shadowMass(width, height, top, bottom, rows) { x, y -> min(1.0, alpha(x, y) + code) }
    return ShadowMassReference(expected, low.below / high.above, high.below / low.above)
}
