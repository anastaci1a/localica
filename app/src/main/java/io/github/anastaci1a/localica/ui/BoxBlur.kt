package io.github.anastaci1a.localica.ui

/** Separable ARGB box filter with clamped edges and integer rounding after each axis.
 * Callers own premultiplication, sampling resolution and the number of passes.
 */
internal fun boxBlur(input: IntArray, width: Int, height: Int, radius: Int, checkLine: () -> Unit = {}): IntArray {
    require(width > 0 && height > 0 && input.size == width * height && radius >= 0)
    fun pass(source: IntArray, horizontal: Boolean): IntArray {
        val result = IntArray(source.size)
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            checkLine()
            fun at(position: Int): Int = if (horizontal) line * width + position.coerceIn(0, width - 1)
                else position.coerceIn(0, height - 1) * width + line
            var a = 0; var r = 0; var g = 0; var b = 0
            fun add(position: Int, sign: Int) {
                val color = source[at(position)]
                a += (color ushr 24) * sign; r += (color ushr 16 and 255) * sign
                g += (color ushr 8 and 255) * sign; b += (color and 255) * sign
            }
            for (position in -radius..radius) add(position, 1)
            for (position in 0 until length) {
                result[at(position)] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                add(position - radius, -1); add(position + radius + 1, 1)
            }
        }
        return result
    }
    return pass(pass(input, true), false)
}
