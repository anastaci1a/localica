package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Android 8–11 fallback: shared, bounded, off-main blur with premultiplied alpha. */
internal suspend fun blurGlassSnapshot(image: ImageBitmap, radius: Float): ImageBitmap {
    val scale = if (radius < 4f) 1 else 4
    val original = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    val w = (original.width / scale).coerceAtLeast(1)
    val h = (original.height / scale).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(original, w, h, true)
    if (original !== small) original.recyclePreparedSnapshot("glass-blur-original")
    var pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    small.recyclePreparedSnapshot("glass-blur-small")
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
