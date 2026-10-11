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
    if (original !== small) original.recycle()
    var pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    small.recycle()
    pixels.indices.forEach { index ->
        val color = pixels[index]; val a = color ushr 24
        pixels[index] = (a shl 24) or (((color ushr 16 and 255) * a / 255) shl 16) or
            (((color ushr 8 and 255) * a / 255) shl 8) or ((color and 255) * a / 255)
    }
    val reach = (radius / scale / 2f).roundToInt().coerceAtLeast(1)
    val count = reach * 2 + 1
    suspend fun pass(horizontal: Boolean) {
        val result = IntArray(pixels.size)
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        for (line in 0 until lines) {
            currentCoroutineContext().ensureActive()
            fun at(position: Int): Int = if (horizontal) line * w + position.coerceIn(0, w - 1)
                else position.coerceIn(0, h - 1) * w + line
            var a = 0; var r = 0; var g = 0; var b = 0
            fun add(position: Int, sign: Int) {
                val color = pixels[at(position)]
                a += (color ushr 24) * sign; r += (color ushr 16 and 255) * sign
                g += (color ushr 8 and 255) * sign; b += (color and 255) * sign
            }
            for (position in -reach..reach) add(position, 1)
            for (position in 0 until length) {
                result[at(position)] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                add(position - reach, -1); add(position + reach + 1, 1)
            }
        }
        pixels = result
    }
    repeat(3) { pass(true); pass(false) }
    pixels.indices.forEach { index ->
        val color = pixels[index]; val a = color ushr 24
        pixels[index] = if (a == 0) 0 else (a shl 24) or
            (((color ushr 16 and 255) * 255 / a).coerceAtMost(255) shl 16) or
            (((color ushr 8 and 255) * 255 / a).coerceAtMost(255) shl 8) or
            ((color and 255) * 255 / a).coerceAtMost(255)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
