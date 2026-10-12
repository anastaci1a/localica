package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BoxBlurTest {
    @Test fun pixelsMatchScalarReferenceIncludingAlphaEdgesAndLargeRadii() {
        val random = Random(83)
        for ((width, height) in listOf(1 to 1, 1 to 7, 8 to 1, 7 to 9)) {
            for (radius in listOf(0, 1, 2, 12)) {
                val pixels = IntArray(width * height) { random.nextInt() }
                fun reference(source: IntArray, horizontal: Boolean) = IntArray(source.size) { index ->
                    val x = index % width; val y = index / width
                    (0..3).fold(0) { color, channel ->
                        val total = (-radius..radius).sumOf { delta ->
                            val sx = if (horizontal) (x + delta).coerceIn(0, width - 1) else x
                            val sy = if (horizontal) y else (y + delta).coerceIn(0, height - 1)
                            source[sy * width + sx] ushr (channel * 8) and 255
                        }
                        color or ((total / (radius * 2 + 1)) shl (channel * 8))
                    }
                }
                assertArrayEquals(reference(reference(pixels, true), false), boxBlur(pixels, width, height, radius))
            }
        }
    }

    @Test fun cancellationCanStopFilteringBetweenLines() {
        var lines = 0
        try {
            boxBlur(IntArray(64), 8, 8, 3) { if (++lines == 3) throw InterruptedException() }
            fail("Cancellation must propagate")
        } catch (_: InterruptedException) { assertEquals(3, lines) }
    }
}
