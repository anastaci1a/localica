package io.github.anastaci1a.localica

import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import kotlin.math.abs
import kotlin.math.roundToInt

/** Roborazzi passes the actual image on the left and the golden on the right. */
internal object PilotImageComparator : ImageComparator {
    override fun compare(left: Image, right: Image, diff: Mask?): ImageComparator.ComparisonResult {
        val width = maxOf(left.width, right.width)
        val height = maxOf(left.height, right.height)
        if (left.width != right.width || left.height != right.height)
            return ImageComparator.ComparisonResult(width * height, width * height, width, height)
        var band = 0
        var changed = 0
        var invalid = false
        for (y in 0 until height) for (x in 0 until width) {
            val expected = right.getPixel(x, y)
            val edge = (maxOf(0, y - 1)..minOf(height - 1, y + 1)).any { row ->
                (maxOf(0, x - 1)..minOf(width - 1, x + 1)).any { column -> right.getPixel(column, row) != expected }
            }
            if (edge) band++
            val actual = left.getPixel(x, y)
            if (actual != expected) {
                changed++
                fun delta(a: Float, b: Float) = abs((a * 255).roundToInt() - (b * 255).roundToInt())
                if (!edge || maxOf(delta(actual.r, expected.r), delta(actual.g, expected.g),
                        delta(actual.b, expected.b), delta(actual.a, expected.a)) > 3) invalid = true
                diff?.setValue(x, y, 1f)
            }
        }
        return ImageComparator.ComparisonResult(
            if (invalid || changed.toLong() * 200 > band) changed else 0, width * height, width, height,
        )
    }
}
