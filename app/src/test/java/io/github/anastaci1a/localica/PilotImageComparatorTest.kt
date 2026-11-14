package io.github.anastaci1a.localica

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import org.junit.Assert.*
import org.junit.Test

class PilotImageComparatorTest {
    private class Pixels(override val width: Int = 20, override val height: Int = 20,
        val pixel: (Int, Int) -> Color = { x, y -> Color(if ((x + y) % 2 == 0) 100 else 150, 100, 100) }) : Image {
        override fun getPixel(x: Int, y: Int) = pixel(x, y)
    }
    private fun misses(actual: Image, golden: Image = Pixels()) = PilotImageComparator.compare(actual, golden).pixelDifferences

    @Test fun identicalPixelsAndDimensionsPass() = assertEquals(0, misses(Pixels()))
    @Test fun atMostHalfPercentOfEdgePixelsMayDifferByThreeChannels() {
        assertEquals(0, misses(Pixels { x, y ->
            if (y == 0 && x < 2) Color(if (x == 0) 103 else 153, 103, 103) else Pixels().getPixel(x, y)
        }))
        assertTrue(misses(Pixels { x, y ->
            if (y == 0 && x < 3) Color(if (x % 2 == 0) 103 else 153, 103, 103) else Pixels().getPixel(x, y)
        }) > 0)
    }
    @Test fun interiorToneAlphaAndDimensionsRemainExact() {
        val solid = Pixels { _, _ -> Color(100, 100, 100) }
        assertTrue(misses(Pixels { x, y -> if (x == 10 && y == 10) Color(101, 100, 100) else solid.getPixel(x, y) }, solid) > 0)
        assertTrue(misses(Pixels { x, y -> if (x == 0 && y == 0) Color(100, 100, 100, 251) else Pixels().getPixel(x, y) }) > 0)
        assertTrue(misses(Pixels(width = 19)) > 0)
    }
    @Test fun shiftAndFourChannelEdgeDifferenceFail() {
        assertTrue(misses(Pixels { x, y -> Pixels().getPixel((x + 1) % 20, y) }) > 0)
        assertTrue(misses(Pixels { x, y -> if (x == 0 && y == 0) Color(104, 100, 100) else Pixels().getPixel(x, y) }) > 0)
    }
}
