package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream

/** Frozen pre-encoding decode/orientation operations; caller owns returned pixels. */
internal fun originalCoverDecode(bytes: ByteArray, edge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format is not supported." }
        var sample = 1
        while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn’t decode the image.")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
        } }
        val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (image !== decoded) decoded.recycle()
        return image
}
