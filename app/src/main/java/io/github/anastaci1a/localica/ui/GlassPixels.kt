package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Owned software pixels. Callers retain their distinct alpha/filter policies. */
internal class GlassPixels(val width: Int, val height: Int, val pixels: IntArray)

/** Read eligible immutable full-scale sRGB pixels into an owned array; normalize other inputs before sampling.
 * Only temporary copies are retired; the caller's image is never recycled.
 * The caller keeps input lifetime and metadata stable throughout the read.
 * [onSoftwareCopy] reports the normalization copy's allocationByteCount, not total/unique native memory. */
internal fun readGlassPixels(image: ImageBitmap, scale: Int, onSoftwareCopy: ((Int) -> Unit)? = null): GlassPixels {
    require(scale > 0)
    val input = image.asAndroidBitmap()
    if (scale == 1 && input.config == Bitmap.Config.ARGB_8888 && !input.isMutable &&
        input.isPremultiplied && input.colorSpace?.isSrgb == true) {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return GlassPixels(input.width, input.height, pixels)
    }
    val original = checkNotNull(input.copy(Bitmap.Config.ARGB_8888, false))
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
