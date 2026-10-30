package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlassPixelReadReuseTest {
    private fun pattern(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        for (y in 0 until height) for (x in 0 until width) it.setPixel(x, y,
            Color.argb((x * 37 + y * 61) % 256, (x * 29 + y * 7) % 256,
                (x * 11 + y * 43) % 256, (x * 71 + y * 19) % 256))
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun equal(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(pixels(expected), pixels(actual))
    }

    @Test fun immutableFullScalePixelsAndTransformsMatchFrozenWithoutReadCopy() = runBlocking {
        for ((width, height) in listOf(1 to 1, 3 to 7, 35 to 19)) {
            val source = pattern(width, height)
            val inputs = mutableListOf<Pair<String, Bitmap>>()
            try {
                inputs += "immutable-copy" to requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
                inputs += "actual-software-snapshot" to softwareGlassSnapshot(source.asImageBitmap(), IntSize(width, height)).asAndroidBitmap()
                for ((kind, input) in inputs) {
                    assertFalse(input.isMutable)
                    assertTrue(input.isPremultiplied)
                    assertTrue(input.colorSpace?.isSrgb == true)
                    val before = pixels(input)
                    val baselineCopies = mutableListOf<Int>()
                    val candidateCopies = mutableListOf<Int>()
                    val expected = originalReadGlassPixels(input.asImageBitmap(), 1) { baselineCopies += it }
                    val actual = readGlassPixels(input.asImageBitmap(), 1) { candidateCopies += it }
                    assertEquals(expected.width, actual.width)
                    assertEquals(expected.height, actual.height)
                    assertArrayEquals(expected.pixels, actual.pixels)
                    assertEquals(1, baselineCopies.size)
                    assertTrue(baselineCopies.single() > 0)
                    assertTrue(candidateCopies.isEmpty())
                    actual.pixels.fill(Color.MAGENTA)
                    assertArrayEquals(before, pixels(input))
                    assertArrayEquals(expected.pixels, readGlassPixels(input.asImageBitmap(), 1).pixels)
                    for (radius in listOf(0f, 1f, 3.99f, 4f, 12f, 31f)) {
                        val old = originalBlurGlassSnapshot(input.asImageBitmap(), radius).asAndroidBitmap()
                        var prepared: Bitmap? = null
                        try {
                            prepared = blurGlassSnapshot(input.asImageBitmap(), radius).asAndroidBitmap()
                            equal(old, prepared)
                        } finally { prepared?.recycle(); old.recycle() }
                    }
                    val image = input.asImageBitmap()
                    for (amount in listOf(-1f, 0f, .001f, .25f, .75f, 1f, 2f)) {
                        val old = originalTintLensSnapshot(image, amount)
                        var prepared: androidx.compose.ui.graphics.ImageBitmap? = null
                        try {
                            prepared = tintLensSnapshot(image, amount)
                            if (amount <= 0f) { assertSame(image, old); assertSame(image, prepared) }
                            else equal(old.asAndroidBitmap(), prepared.asAndroidBitmap())
                        } finally {
                            if (prepared != null && prepared !== image) prepared.asAndroidBitmap().recycle()
                            if (old !== image) old.asAndroidBitmap().recycle()
                        }
                    }
                    assertFalse(input.isRecycled)
                    assertArrayEquals(before, pixels(input))
                    Log.i("GlassPixelReadWork", "kind=$kind size=${width}x$height scale=1 baselineCopies=${baselineCopies.size} " +
                        "baselineReportedCopyBytes=${baselineCopies.sum()} candidateCopies=${candidateCopies.size} candidateReportedCopyBytes=${candidateCopies.sum()}")
                }
            } finally {
                inputs.forEach { (_, bitmap) -> if (!bitmap.isRecycled) bitmap.recycle() }
                source.recycle()
            }
        }
    }

    @Test fun otherInputsAndSamplingRetainOriginalNormalization() {
        for ((width, height) in listOf(1 to 1, 3 to 7, 35 to 19)) {
            val source = pattern(width, height)
            val owned = mutableListOf<Bitmap>()
            fun own(bitmap: Bitmap) = bitmap.also { owned += it }
            try {
                val immutable = own(requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)))
                val hardware = own(requireNotNull(source.copy(Bitmap.Config.HARDWARE, false)))
                val rgb565 = own(requireNotNull(source.copy(Bitmap.Config.RGB_565, false)))
                val wideMutable = own(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
                    ColorSpace.get(ColorSpace.Named.DISPLAY_P3)))
                wideMutable.setPixels(pixels(source), 0, width, 0, 0, width, height)
                val wide = own(requireNotNull(wideMutable.copy(Bitmap.Config.ARGB_8888, false)))
                assertFalse(wide.colorSpace?.isSrgb == true)
                for ((kind, input) in listOf("mutable" to source, "hardware" to hardware, "rgb565" to rgb565,
                    "display-p3" to wide, "scaled-immutable" to immutable)) {
                    for (scale in if (kind == "scaled-immutable") listOf(4, 8) else listOf(1, 4, 8)) {
                        val baselineCopies = mutableListOf<Int>()
                        val candidateCopies = mutableListOf<Int>()
                        val expected = originalReadGlassPixels(input.asImageBitmap(), scale) { baselineCopies += it }
                        val actual = readGlassPixels(input.asImageBitmap(), scale) { candidateCopies += it }
                        assertEquals(expected.width, actual.width)
                        assertEquals(expected.height, actual.height)
                        assertArrayEquals(expected.pixels, actual.pixels)
                        assertEquals(1, baselineCopies.size)
                        assertEquals(1, candidateCopies.size)
                        assertTrue(baselineCopies.single() > 0)
                        assertTrue(candidateCopies.single() > 0)
                        assertFalse(input.isRecycled)
                    }
                }
            } finally {
                owned.asReversed().forEach { if (!it.isRecycled) it.recycle() }
                source.recycle()
            }
        }
    }
}
