package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.anastaci1a.localica.ui.blurGlassSnapshot
import io.github.anastaci1a.localica.ui.readGlassPixels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlassPixelBufferTest {
    private fun pattern(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.argb((x * 37 + y * 61) % 256,
                    (x * 29 + y * 7) % 256, (x * 11 + y * 43) % 256, (x * 71 + y * 19) % 256))
            }
        }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    @Test fun softwareAndHardwareCopiesMatchOriginalSamplingWithoutOwningInputs() {
        for ((width, height) in listOf(1 to 1, 3 to 7, 35 to 19)) {
            val source = pattern(width, height)
            val hardware = requireNotNull(source.copy(Bitmap.Config.HARDWARE, false))
            try {
                for (input in listOf(source, hardware)) for (scale in listOf(1, 4, 8)) {
                    val original = requireNotNull(input.copy(Bitmap.Config.ARGB_8888, false))
                    var small: Bitmap? = null
                    try {
                        val w = (original.width / scale).coerceAtLeast(1)
                        val h = (original.height / scale).coerceAtLeast(1)
                        val sampled = Bitmap.createScaledBitmap(original, w, h, true)
                        small = sampled
                        val expected = pixels(sampled)
                        val actual = readGlassPixels(input.asImageBitmap(), scale)
                        assertEquals(w, actual.width)
                        assertEquals(h, actual.height)
                        assertArrayEquals(expected, actual.pixels)
                        assertFalse(input.isRecycled)
                        // Returned arrays are independent, even when no downsampling occurs.
                        actual.pixels.fill(Color.MAGENTA)
                        assertArrayEquals(expected, readGlassPixels(input.asImageBitmap(), scale).pixels)
                    } finally {
                        if (small !== original) small?.recycle()
                        original.recycle()
                    }
                }
                val before = pixels(source)
                for (scale in listOf(0, -1)) {
                    try {
                        readGlassPixels(source.asImageBitmap(), scale)
                        fail("Invalid sampling scale")
                    } catch (_: IllegalArgumentException) { }
                }
                assertArrayEquals(before, pixels(source))
                assertFalse(source.isRecycled)
            } finally { hardware.recycle(); source.recycle() }
        }
    }

    @Test fun transparentBlurMatchesFrozenOriginalAtBothSamplingScales() = runBlocking {
        for ((width, height) in listOf(1 to 1, 3 to 7, 35 to 19)) {
            val source = pattern(width, height)
            try {
                val before = pixels(source)
                for (radius in listOf(0f, 1f, 3.99f, 4f, 12f, 31f)) {
                    var expected: Bitmap? = null
                    var actual: Bitmap? = null
                    try {
                        val baseline = originalBlurGlassSnapshot(source.asImageBitmap(), radius).asAndroidBitmap()
                        expected = baseline
                        val prepared = blurGlassSnapshot(source.asImageBitmap(), radius).asAndroidBitmap()
                        actual = prepared
                        assertEquals(baseline.width, prepared.width)
                        assertEquals(baseline.height, prepared.height)
                        assertArrayEquals("Exact old premultiplied integer result at $width/$height/$radius",
                            pixels(baseline), pixels(prepared))
                        assertFalse(source.isRecycled)
                        assertArrayEquals(before, pixels(source))
                    } finally { actual?.recycle(); expected?.recycle() }
                }
            } finally { source.recycle() }
        }
    }
}
