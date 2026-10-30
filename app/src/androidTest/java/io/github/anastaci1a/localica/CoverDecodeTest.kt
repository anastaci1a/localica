package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CoverDecodeTest {
    private fun pattern() = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until 24) for (x in 0 until 40) bitmap.setPixel(x, y,
            Color.argb((x * 37 + y * 61) % 256, (x * 29) % 256, (y * 43) % 256, (x * 71 + y * 19) % 256))
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test fun decodedPixelsMatchBeforeEncodingWhileEncodedReadRetainsOriginalBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cover-decode-", ".image", context.cacheDir)
        val source = pattern()
        try {
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
                val orientations = if (format == Bitmap.CompressFormat.JPEG) 1..8 else 1..1
                for (orientation in orientations) {
                    file.outputStream().use { assertTrue(source.compress(format, 90, it)) }
                    if (format == Bitmap.CompressFormat.JPEG) {
                        // Android 8 ExifInterface requires a 5000-byte format probe. A valid
                        // JPEG comment keeps this tiny fixture above it without changing pixels.
                        val jpeg = file.readBytes()
                        assertEquals(0xff, jpeg[0].toInt() and 0xff)
                        assertEquals(0xd8, jpeg[1].toInt() and 0xff)
                        file.outputStream().use { output ->
                            output.write(jpeg, 0, 2)
                            output.write(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x13, 0x8a.toByte()))
                            output.write(ByteArray(5000)) // COM length 5002 includes its two length bytes.
                            output.write(jpeg, 2, jpeg.size - 2)
                        }
                        ExifInterface(file.path).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                        }
                        assertEquals(orientation, ExifInterface(java.io.ByteArrayInputStream(file.readBytes()))
                            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                    }
                    val bytes = file.readBytes()
                    val uri = Uri.fromFile(file).toString()
                    for (edge in listOf(8, 32, 128)) {
                        val expected = originalCoverDecode(bytes, edge)
                        var actual: Bitmap? = null
                        try {
                            var decodeCallbacks = 0
                            val decoded = CoverImages(context).decode(uri, edge) { decodeCallbacks++ }
                            actual = decoded
                            assertEquals(1, decodeCallbacks)
                            assertEquals(expected.width, decoded.width)
                            assertEquals(expected.height, decoded.height)
                            assertEquals(expected.hasAlpha(), decoded.hasAlpha())
                            assertArrayEquals(pixels(expected), pixels(decoded))
                            val output = ByteArrayOutputStream()
                            val alpha = expected.hasAlpha()
                            assertTrue(expected.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, output))
                            val encoded = CoverImages(context).read(uri, edge)
                            assertEquals(expected.width, encoded.width)
                            assertEquals(expected.height, encoded.height)
                            assertEquals(if (alpha) "image/png" else "image/jpeg", encoded.mime)
                            assertArrayEquals(output.toByteArray(), encoded.bytes)
                        } finally { actual?.recycle(); expected.recycle() }
                    }
                }
            }
        } finally { source.recycle(); file.delete() }
    }

    @Test fun eachDecodeOwnsIndependentPixelsAndLeavesTheInputFileUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cover-decode-owned-", ".png", context.cacheDir)
        val source = pattern()
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val original = file.readBytes()
            val images = CoverImages(context)
            val uri = Uri.fromFile(file).toString()
            val one = images.decode(uri, 128); first = one
            val two = images.decode(uri, 128); second = two
            assertNotSame(one, two)
            assertArrayEquals(pixels(one), pixels(two))
            val untouched = pixels(two)
            one.recycle()
            assertTrue(one.isRecycled)
            assertFalse(two.isRecycled)
            assertArrayEquals(untouched, pixels(two))
            assertArrayEquals(original, file.readBytes())
        } finally { first?.recycle(); second?.recycle(); source.recycle(); file.delete() }
    }

    @Test fun cancellationDuringDecodeCannotPublishAcrossTheDispatcherHandoff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cover-decode-cancel-", ".png", context.cacheDir)
        val source = pattern()
        var published = false
        var callbacks = 0
        var worker: Job? = null
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val job = launch(start = CoroutineStart.LAZY) {
                val image = CoverImages(context).decode(Uri.fromFile(file).toString(), 128) {
                    callbacks++
                    requireNotNull(worker).cancel()
                }
                try { published = true } finally { image.recycle() }
            }
            worker = job
            job.start()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(1, callbacks)
            assertFalse(published)
            assertTrue(file.length() > 0)
        } finally {
            worker?.let { job ->
                job.cancel()
                withContext(NonCancellable) { withTimeout(5_000) { job.join() } }
            }
            source.recycle(); file.delete()
        }
    }
}
