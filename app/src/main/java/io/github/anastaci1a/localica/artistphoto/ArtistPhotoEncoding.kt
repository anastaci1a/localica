package io.github.anastaci1a.localica.artistphoto

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Store display-sized WebP plus a small row/chooser image; reject undecodable provider payloads. */
object ArtistPhotoEncoding {
    fun encode(bytes: ByteArray): PreparedArtistPhoto {
        val display = Resources.getSystem().displayMetrics
        val largest = minOf(1080, maxOf(display.widthPixels, display.heightPixels)).coerceAtLeast(256)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > largest * 2) options.inSampleSize *= 2
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        fun scaled(limit: Int): Bitmap {
            val dimension = maxOf(decoded.width, decoded.height)
            if (dimension <= limit) return decoded
            return Bitmap.createScaledBitmap(decoded, (decoded.width.toLong() * limit / dimension).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * limit / dimension).toInt().coerceAtLeast(1), true)
        }
        fun encoded(limit: Int): ByteArray {
            val image = scaled(limit)
            try {
                val output = ByteArrayOutputStream()
                check(image.compress(Bitmap.CompressFormat.WEBP, 85, output))
                return output.toByteArray().also { require(it.size in 1..PHOTO_MAX_BYTES) }
            } finally { if (image !== decoded) image.recycle() }
        }
        return try { PreparedArtistPhoto(encoded(largest), encoded(256)) } finally { decoded.recycle() }
    }
}
