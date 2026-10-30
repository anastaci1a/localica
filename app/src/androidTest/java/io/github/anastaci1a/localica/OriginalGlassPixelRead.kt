package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.GlassPixels

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Copy before reading or scaling: the input can be hardware-backed or shared.
 * Only these temporary copies are retired; the caller's image is never recycled. */
internal fun originalReadGlassPixels(image: ImageBitmap, scale: Int, onSoftwareCopy: ((Int) -> Unit)? = null): GlassPixels {
    require(scale > 0)
    val original = checkNotNull(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
    var small: Bitmap? = null
    try {
        onSoftwareCopy?.invoke(original.allocationByteCount)
        val width = (original.width / scale).coerceAtLeast(1)
        val height = (original.height / scale).coerceAtLeast(1)
        val sampled = Bitmap.createScaledBitmap(original, width, height, true)
        small = sampled
        if (sampled !== original) original.recycle()
        val pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        return GlassPixels(width, height, pixels)
    } finally {
        if (small !== original) small?.recycle()
        if (!original.isRecycled) original.recycle()
    }
}
