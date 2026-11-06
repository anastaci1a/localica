package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GlassAverageTest {
    @Test fun premultipliedAverageDoesNotAdmitColourFromTransparentSamples() {
        val average = averageGlassColor { x, _ -> if (x < 4) 0xFFFF0000.toInt() else 0x000000FF }
        assertEquals(1f, average.red, .00001f)
        assertEquals(0f, average.blue, .00001f)
        assertEquals(128f / 255f, average.alpha, .00001f)
        assertEquals(Color.Transparent, averageGlassColor { _, _ -> 0x00FFFFFF })
    }

    @Test fun uniformColoursRemainUniformAcrossAlphaAndAllChannels() {
        val random = Random(600)
        repeat(1000) {
            val argb = random.nextInt()
            val average = averageGlassColor { _, _ -> argb }
            val expected = if (argb ushr 24 == 0) Color.Transparent else Color(argb)
            assertEquals(expected.red, average.red, .00001f)
            assertEquals(expected.green, average.green, .00001f)
            assertEquals(expected.blue, average.blue, .00001f)
            assertEquals(expected.alpha, average.alpha, .00001f)
        }
    }
}
