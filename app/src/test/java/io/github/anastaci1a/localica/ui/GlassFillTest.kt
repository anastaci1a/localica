package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.*
import org.junit.Test

class GlassFillTest {
    private val rows = arrayOf(
        doubleArrayOf(.5400391, -.2017822, -.0203094, .7001953),
        doubleArrayOf(-.0599365, .3984375, -.0203705, .7001953),
        doubleArrayOf(-.0599976, -.2016602, .5795898, .7001953))

    @Test fun matrixRowsAndUpperClampMatchTheSpecifiedValues() {
        for (channel in 0..2) {
            assertEquals(rows[channel][3], glassFillChannel(channel, 0f, 0f, 0f).toDouble(), 1e-7)
            for (axis in 0..2) {
                val sample = FloatArray(3).also { it[axis] = .25f }
                assertEquals(rows[channel][axis] * .25 + rows[channel][3],
                    glassFillChannel(channel, sample[0], sample[1], sample[2]).toDouble(), 1e-7)
            }
            assertEquals(1.06961f, glassFillChannel(channel, 4f, 4f, 4f), 0f)
        }
        assertFalse(GlassColorShader.contains("whiteTint"))
        assertTrue(GlassColorShader.contains("float3 glassColor(float3 color)"))
        assertTrue(GlassColorShader.contains("1.06961"))
    }

    @Test fun encodedRgbCubeMatchesTheIndependentMatrixAndSdrAdapterPreservesAlpha() {
        for (r in 0..16) for (g in 0..16) for (b in 0..16) {
            val rgb = floatArrayOf(r / 16f, g / 16f, b / 16f)
            for (channel in 0..2) {
                val row = rows[channel]
                val expected = minOf(1.06961, row[0] * rgb[0] + row[1] * rgb[1] + row[2] * rgb[2] + row[3])
                assertEquals(expected, glassFillChannel(channel, rgb[0], rgb[1], rgb[2]).toDouble(), 2e-7)
            }
            for (alpha in listOf(0f, .25f, 1f)) {
                val mapped = MaterialAverageStyle().color(Color(rgb[0], rgb[1], rgb[2], alpha))
                assertEquals(alpha, mapped.alpha, 1f / 255)
                for ((channel, actual) in listOf(mapped.red, mapped.green, mapped.blue).withIndex())
                    assertEquals(glassFillChannel(channel, rgb[0], rgb[1], rgb[2]).coerceIn(0f, 1f), actual, 1f / 255)
            }
        }
    }

    @Test fun broadBackdropGradeRemainsTheExistingSeparateRole() {
        for (r in 0..8) for (g in 0..8) for (b in 0..8) for (alpha in listOf(.25f, 1f)) {
            val sample = Color(r / 8f, g / 8f, b / 8f, alpha)
            for (light in listOf(false, true)) for (trim in listOf(0f, .25f, .5f)) {
                val tone = GlassTone(trim, 2f, light)
                val y = .2126f * sample.red + .7152f * sample.green + .0722f * sample.blue
                val lifted = 1f - (1f - y)
                val amount = ((lifted - y) / (1f - y).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
                val original = tone.color(lerp(sample, Color.White.copy(alpha = sample.alpha), amount))
                assertEquals("Broad grade preserves original bytes for $sample $tone", original, MaterialAverageStyle(tone).color(sample))
                assertEquals(original.copy(alpha = 1f), MaterialAverageStyle(tone, opaque = true).color(sample))
            }
        }
    }
}
