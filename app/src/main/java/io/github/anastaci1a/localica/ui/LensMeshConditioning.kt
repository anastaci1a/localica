package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// Android 8 SkMatrix::setPolyToPoly rejects a source first edge or its
// perpendicular altitude <= 1/4096 px. A cyclic permutation can improve that
// basis without changing any coordinate, triangle winding or affine mapping.
private const val TextureBasisMinimum = 1f / 4096f

internal fun conditionLensTextureTriangles(vertices: FloatArray, texture: FloatArray,
    indices: ShortArray, result: ShortArray) {
    indices.copyInto(result)
    for (i in indices.indices step 3) {
        val a = indices[i].toInt() and 0xffff
        val b = indices[i + 1].toInt() and 0xffff
        val c = indices[i + 2].toInt() and 0xffff
        val original = textureBasisQuality(texture, a, b, c)
        if (original > TextureBasisMinimum) continue
        fun quality(x: Int, y: Int, z: Int) = min(textureBasisQuality(texture, x, y, z),
            textureBasisQuality(vertices, x, y, z))
        val first = quality(a, b, c)
        val second = quality(b, c, a)
        val third = quality(c, a, b)
        // Keep genuinely degenerate maps unchanged; this is conditioning, not
        // a substitute optical map or an epsilon perturbation of folded UVs.
        val shift = when {
            second > first && second >= third -> 1
            third > first && third > second -> 2
            else -> 0
        }
        for (j in 0..2) result[i + j] = indices[i + (j + shift) % 3]
    }
}

internal fun textureBasisQuality(points: FloatArray, a: Int, b: Int, c: Int): Float {
    val dx = points[2 * b] - points[2 * a]
    val dy = points[2 * b + 1] - points[2 * a + 1]
    val length = sqrt(dx * dx + dy * dy)
    if (length == 0f) return 0f
    val nx = points[2 * a + 1] - points[2 * c + 1]
    val ny = points[2 * c] - points[2 * a]
    // Keep the Float multiply/add/divide sequence used by legacy Skia.
    return min(length, abs((dx * nx + dy * ny) / length))
}
