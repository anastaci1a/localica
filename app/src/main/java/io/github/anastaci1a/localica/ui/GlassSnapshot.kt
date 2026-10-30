package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** A readback is not necessarily CPU-readable on modern Android. Normalize once
 * at the Legacy publication boundary, including completely clear/untinted glass.
 * This is not blur: retain sharp pixels, alpha and the exact logical source extent.
 * The input may be shared with a native lease/cache and is never recycled here. */
internal suspend fun softwareGlassSnapshot(image: ImageBitmap, size: IntSize): ImageBitmap {
    require(size.width > 0 && size.height > 0 && size.width <= image.width && size.height <= image.height)
    val context = currentCoroutineContext()
    context.ensureActive()
    val software = checkNotNull(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
    var result: Bitmap? = null
    try {
        result = if (software.width == size.width && software.height == size.height) software
            else Bitmap.createBitmap(software, 0, 0, size.width, size.height)
        context.ensureActive()
        return result.asImageBitmap()
    } catch (failure: Throwable) {
        if (result !== software) result?.recycle()
        software.recycle()
        throw failure
    } finally {
        if (result != null && result !== software && !software.isRecycled) software.recycle()
    }
}

/** Android 8–11 fallback: shared, bounded, off-main blur with premultiplied alpha. */
internal suspend fun blurGlassSnapshot(image: ImageBitmap, radius: Float, onReadCopy: ((Int) -> Unit)? = null): ImageBitmap {
    val scale = if (radius < 4f) 1 else 4
    val source = readGlassPixels(image, scale, onReadCopy)
    val w = source.width
    val h = source.height
    var pixels = source.pixels
    pixels.indices.forEach { index ->
        val color = pixels[index]; val a = color ushr 24
        pixels[index] = (a shl 24) or (((color ushr 16 and 255) * a / 255) shl 16) or
            (((color ushr 8 and 255) * a / 255) shl 8) or ((color and 255) * a / 255)
    }
    val reach = (radius / scale / 2f).roundToInt().coerceAtLeast(1)
    val context = currentCoroutineContext()
    repeat(3) { pixels = boxBlur(pixels, w, h, reach) { context.ensureActive() } }
    pixels.indices.forEach { index ->
        val color = pixels[index]; val a = color ushr 24
        pixels[index] = if (a == 0) 0 else (a shl 24) or
            (((color ushr 16 and 255) * 255 / a).coerceAtMost(255) shl 16) or
            (((color ushr 8 and 255) * 255 / a).coerceAtMost(255) shl 8) or
            ((color and 255) * 255 / a).coerceAtMost(255)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
