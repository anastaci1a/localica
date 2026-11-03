package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTintTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(color: Int) = Triple(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)

    @Test fun lightnessIsClampedPerThemeForVeryDarkAndVeryLightArt() {
        for (dark in listOf(true, false)) {
            val min = if (dark) ArtworkTintTuning.DarkMinLightness else ArtworkTintTuning.LightMinLightness
            val max = if (dark) ArtworkTintTuning.DarkMaxLightness else ArtworkTintTuning.LightMaxLightness
            for (art in listOf(rgb(0, 0, 0), rgb(8, 4, 20), rgb(255, 255, 255), rgb(250, 248, 240), rgb(255, 0, 0), rgb(0, 40, 200))) {
                val l = oklabLightness(placeholderTint(art, dark))
                assertTrue("dark=$dark art=${Integer.toHexString(art)} L=$l", l >= min - .015f && l <= max + .015f)
            }
        }
    }

    @Test fun midToneArtKeepsItsLightnessAndHue() {
        val art = rgb(60, 110, 170)
        val tint = placeholderTint(art, dark = true)
        assertEquals(oklabLightness(art), oklabLightness(tint), .01f)
        val (r, g, b) = channels(tint)
        assertTrue("Blue stays dominant: ${channels(tint)}", b > g && g > r)
    }

    @Test fun saturatedArtIsSoftenedAndNeutralArtStaysNeutral() {
        val (r, g, b) = channels(placeholderTint(rgb(255, 0, 0), dark = false))
        assertTrue("Chroma is capped: ${Triple(r, g, b)}", r - maxOf(g, b) < 150)
        val (gr, gg, gb) = channels(placeholderTint(rgb(128, 128, 128), dark = true))
        assertTrue(kotlin.math.abs(gr - gg) <= 1 && kotlin.math.abs(gg - gb) <= 1)
    }

    @Test fun resultIsOpaque() {
        assertEquals(0xFF, placeholderTint(rgb(10, 200, 30), dark = true) ushr 24)
    }
}
