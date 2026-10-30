package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.GlassTone
import io.github.anastaci1a.localica.ui.ToneSnapshotReceipt
import io.github.anastaci1a.localica.ui.rememberToneSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassTonePixelBufferTest {
    @get:Rule val compose = createComposeRule()

    private data class Request(val image: ImageBitmap, val tone: GlassTone)
    private data class Published(val request: Request, val output: ImageBitmap?,
        val receipt: ToneSnapshotReceipt<Bitmap>?)

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    // Frozen previous copy/LUT/output algorithm; curve itself remains the shared dependency.
    private fun original(image: ImageBitmap, tone: GlassTone): Bitmap {
        val original = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(original.width * original.height)
        original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
        val lut = IntArray(256) { tone.byteChannel(it) }
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (c and -0x1000000) or (lut[c ushr 16 and 255] shl 16) or
                (lut[c ushr 8 and 255] shl 8) or lut[c and 255]
        }
        return Bitmap.createBitmap(pixels, original.width, original.height, Bitmap.Config.ARGB_8888)
            .also { original.recycle() }
    }

    @Test fun realTonePreparationMatchesPreviousPixelsAndReusesItsCachedOutput() {
        // These tiny sources and prepared outputs remain GC-owned: the production cache
        // may retain them, so the fixture must not recycle a published/shared bitmap.
        val software = Bitmap.createBitmap(35, 19, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.argb((x * 37 + y * 61) % 256,
                    (x * 29 + y * 7) % 256, (x * 11 + y * 43) % 256, (x * 71 + y * 19) % 256))
            }
        }
        val hardware = requireNotNull(software.copy(Bitmap.Config.HARDWARE, false))
        val tones = listOf(GlassTone(.001f, 1f, false), GlassTone(.25f, 1.5f, true),
            GlassTone(.5f, 2f, false), GlassTone(.5f, 2f, true))
        var request by mutableStateOf(Request(software.asImageBitmap(), GlassTone()))
        var published: Published? = null
        compose.setContent {
            val current = request
            var receipt: ToneSnapshotReceipt<Bitmap>? = null
            val output = rememberToneSnapshot(current.image, current.tone, onReceipt = { receipt = it })
            SideEffect { published = Published(current, output, receipt) }
        }
        fun await(current: Request): ImageBitmap {
            compose.runOnIdle { request = current }
            compose.waitUntil(5_000) {
                published?.let { it.request == current && it.output != null &&
                    it.receipt?.matches(current.image.asAndroidBitmap(), it.output.asAndroidBitmap(), current.tone) == true
                } == true
            }
            return compose.runOnIdle { requireNotNull(published?.output) }
        }
        for (bitmap in listOf(software, hardware)) {
            val image = bitmap.asImageBitmap()
            assertSame(image, await(Request(image, GlassTone())))
            for (tone in tones) {
                val current = Request(image, tone)
                val expected = original(image, tone)
                try {
                    val output = await(current)
                    assertArrayEquals(pixels(expected), pixels(output.asAndroidBitmap()))
                    assertFalse(bitmap.isRecycled)
                    assertSame(image, await(Request(image, GlassTone())))
                    assertSame(output, await(current))
                } finally { expected.recycle() }
            }
        }
    }
}
