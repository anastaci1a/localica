package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class SelectionDistanceFieldTest {
    @Test fun distancesMatchNearestOutsidePixelsAcrossJoinedShapesAndHoles() {
        val width = 43; val height = 37
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            if ((x in 2..25 && y in 3..30 || x in 21..39 && y in 9..34) && !(x in 10..14 && y in 15..19)) -1 else 0
        }
        val actual = selectionDistances(pixels, width, height)
        val outside = pixels.indices.filter { pixels[it] == 0 }
        for (i in pixels.indices) {
            val squared = outside.minOf { j ->
                val dx = i % width - j % width; val dy = i / width - j / width
                dx * dx + dy * dy
            }
            assertEquals("Distance at $i", sqrt(squared.toFloat()), actual[i], .001f)
        }
    }
}
