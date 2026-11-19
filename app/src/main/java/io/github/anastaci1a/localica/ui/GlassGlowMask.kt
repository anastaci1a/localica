package io.github.anastaci1a.localica.ui

import kotlin.math.*
import androidx.compose.ui.geometry.*

internal fun glowReducedRadius(radius: Float): Float {
    val threshold = if (radius <= 4f) 2f else if (radius <= 8f) 4f else 8f
    var r = radius
    while (r > threshold) r *= .5f
    return r
}

internal fun glowDiskSide(sigma: Float) = 2 * (ceil(sigma).toInt() + ceil(sigma * 2.8199565410614014f + .5f).toInt() + 1) + 1

/** A circular source with independently quantized, zero-extended byte convolutions. */
internal fun glowDisk(sigma: Float): IntArray {
    val side = glowDiskSide(sigma)
    val center = side / 2
    val support = ceil(2.5f * sigma).toInt()
    val weights = DoubleArray(support * 2 + 1) { exp(-(it - support).toDouble().pow(2) / (2 * sigma * sigma)) }
    val sum = weights.sum()
    val kernel = IntArray(weights.size) { (weights[it] * 16384 / sum).toInt() }
    val r = sigma.toDouble()
    fun primitive(x: Double) = .5 * (x * sqrt(maxOf(0.0, r * r - x * x)) + r * r * asin(x / r))
    fun quadrant(x: Double, y: Double): Double {
        val xx = minOf(abs(x), r); val yy = minOf(abs(y), r)
        val split = minOf(xx, sqrt(maxOf(0.0, r * r - yy * yy)))
        return sign(x) * sign(y) * (yy * split + primitive(xx) - primitive(split))
    }
    val source = IntArray(side * side) { index ->
        val x = (index % side - center).toDouble(); val y = (index / side - center).toDouble()
        val area = quadrant(x + .5, y + .5) - quadrant(x - .5, y + .5) - quadrant(x + .5, y - .5) + quadrant(x - .5, y - .5)
        (area.toFloat() * 255.99000549316406f).toInt()
    }
    if (sigma <= .6f) return source.map { (it shl 24) or 0xffffff }.toIntArray()
    fun pass(input: IntArray, horizontal: Boolean) = IntArray(input.size) { index ->
        val x = index % side; val y = index / side
        var value = 8192
        for (j in -support..support) {
            val xx = x + if (horizontal) j else 0; val yy = y + if (horizontal) 0 else j
            if (xx in 0 until side && yy in 0 until side) value += input[yy * side + xx] * kernel[j + support]
        }
        value / 16384
    }
    return pass(pass(source, true), false).map { (it shl 24) or 0xffffff }.toIntArray()
}

internal fun glowMatrix(local: Boolean, white: Float): FloatArray {
    fun clean(value: Float) = sign(value) * floor(abs(value) / .0001f + .5f) * .0001f
    val linear = if (white <= .04045f) white / 12.92f else ((white + .055f) / 1.055f).toDouble().pow(2.4).toFloat()
    val encoded = if (linear <= .0031308f) linear * 12.92f else 1.055f * linear.toDouble().pow(1 / 2.4).toFloat() - .055f
    val gain = if (local) { val q = minOf(encoded, 255f / 256f); clean(q / (1f - q) + 1f) } else 1f
    val matrix = if (local) floatArrayOf(1.3937f, -.3577f, -.036f, 0f, 0f, -.1063f, 1.1424f, -.0361f, 0f, -0f,
        -.1064f, -.3575f, 1.4639f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    else floatArrayOf(1.1575f, -.1431f, -.0144f, 0f, .049999997f, -.0425f, 1.0569999f, -.0145f, 0f, .049999997f,
        -.0426f, -.143f, 1.1855999f, 0f, .049999997f, 0f, 0f, 0f, 1f, 0f)
    if (local) for (i in 0..14) matrix[i] = clean(matrix[i] * gain)
    return matrix
}
