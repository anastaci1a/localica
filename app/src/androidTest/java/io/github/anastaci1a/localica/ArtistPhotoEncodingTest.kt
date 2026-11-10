package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.anastaci1a.localica.artistphoto.ArtistPhotoEncoding
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ArtistPhotoEncodingTest {
    @Test fun fullPhotoAndThumbnailAreBoundedWebpAndPreserveAspectRatio() {
        val source = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        val encoded = ArtistPhotoEncoding.encode(bytes)
        for ((image, limit) in listOf(encoded.full to 1080, encoded.thumbnail to 256)) {
            assertEquals("RIFF", String(image, 0, 4, Charsets.US_ASCII))
            assertTrue(image.size < 200 * 1024)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
            try {
                assertTrue(decoded.width <= limit && decoded.height <= limit)
                assertEquals(4f / 3, decoded.width.toFloat() / decoded.height, .01f)
                assertTrue(android.graphics.Color.red(decoded.getPixel(decoded.width / 2, decoded.height / 2)) > 240)
            } finally { decoded.recycle() }
        }
    }

    @Test fun invalidPhotoPayloadCannotBecomeAReadyImage() {
        try { ArtistPhotoEncoding.encode(byteArrayOf(-1, -40, -1, 0)); fail("Undecodable provider image accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
