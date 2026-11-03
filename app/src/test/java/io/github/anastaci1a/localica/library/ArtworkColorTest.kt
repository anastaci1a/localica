package io.github.anastaci1a.localica.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkColorTest {
    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(color: Int) = Triple(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)

    @Test fun solidColourAveragesToItself() {
        val color = rgb(200, 40, 90)
        assertEquals(color, averageArtworkColor(IntArray(256) { color }))
    }

    @Test fun halfBlackHalfWhiteAveragesInLinearLightNotGammaSpace() {
        val pixels = IntArray(256) { if (it % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255) }
        // Linear mean 0.5 encodes to sRGB 188, not the gamma-space midpoint 128.
        val (r, g, b) = channels(averageArtworkColor(pixels))
        assertEquals(188, r); assertEquals(188, g); assertEquals(188, b)
    }

    @Test fun transparencyWeightsPixelsAndResultIsOpaque() {
        val pixels = IntArray(256) { if (it < 128) rgb(255, 0, 0) else rgb(0, 0, 255, 0) }
        val average = averageArtworkColor(pixels)
        assertEquals(0xFF, average ushr 24)
        assertEquals(Triple(255, 0, 0), channels(average))
        assertEquals(rgb(0, 0, 0), averageArtworkColor(IntArray(16) { 0 }))
    }

    @Test fun storedColoursNeverCollideWithTheMissingSentinel() {
        assertEquals(true, averageArtworkColor(IntArray(4) { rgb(0, 0, 0) }) != ArtworkColors.MISSING)
    }

    @Test fun resolveDistinguishesUnknownMissingAndColour() {
        val red = rgb(255, 0, 0)
        val index = mapOf("a" to red, "none" to ArtworkColors.MISSING)
        assertNull(ArtworkColors.resolve(index, emptyList()))
        assertNull(ArtworkColors.resolve(index, listOf("unknown")))
        assertEquals(red, ArtworkColors.resolve(index, listOf("a")))
        assertEquals(ArtworkColors.MISSING, ArtworkColors.resolve(index, listOf("none")))
        assertEquals(red, ArtworkColors.resolve(index, listOf("none", "a")))
        assertNull(ArtworkColors.resolve(index, listOf("none", "unknown")))
    }
}
