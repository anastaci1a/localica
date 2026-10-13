package io.github.anastaci1a.localica.ui

import kotlin.math.sqrt

/** Exact squared Euclidean distance to the nearest outside pixel, in linear time.
 * A padded silhouette guarantees an outside seed in every row. Buffers are reused
 * across scanlines; only geometry changes rebuild this field, never scrolling.
 */
internal fun selectionDistances(pixels: IntArray, width: Int, height: Int): FloatArray {
    val squared = FloatArray(pixels.size) { if ((pixels[it] ushr 24) < 128) 0f else 1e9f }
    val extent = maxOf(width, height)
    val input = FloatArray(extent)
    val output = FloatArray(extent)
    val sites = IntArray(extent)
    val crossings = FloatArray(extent + 1)
    fun transform(count: Int) {
        var k = 0
        sites[0] = 0; crossings[0] = Float.NEGATIVE_INFINITY; crossings[1] = Float.POSITIVE_INFINITY
        for (q in 1 until count) {
            fun crossing(): Float {
                val v = sites[k]
                return ((input[q] + q.toFloat() * q) - (input[v] + v.toFloat() * v)) / (2f * (q - v))
            }
            var at = crossing()
            while (at <= crossings[k]) { k--; at = crossing() }
            k++; sites[k] = q; crossings[k] = at; crossings[k + 1] = Float.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until count) {
            while (crossings[k + 1] < q) k++
            val delta = q - sites[k]
            output[q] = delta.toFloat() * delta + input[sites[k]]
        }
    }
    for (y in 0 until height) {
        for (x in 0 until width) input[x] = squared[y * width + x]
        transform(width)
        for (x in 0 until width) squared[y * width + x] = output[x]
    }
    for (x in 0 until width) {
        for (y in 0 until height) input[y] = squared[y * width + x]
        transform(height)
        for (y in 0 until height) squared[y * width + x] = sqrt(output[y])
    }
    return squared
}
