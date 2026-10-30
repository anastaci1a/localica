package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Same ordered shadow-lift curve as the lens shader, prepared once per shared
 * Android 8–12 source/setting change rather than once per control or gesture frame. */
internal fun originalTintLensSnapshot(image: ImageBitmap, amount: Float): ImageBitmap {
    val tint = amount.coerceIn(0f, 1f)
    if (tint == 0f) return image
    val original = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(original.width * original.height)
    original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = ((c ushr 16) and 255) / 255f; val g = ((c ushr 8) and 255) / 255f; val b = (c and 255) / 255f
        val y = .2126f * r + .7152f * g + .0722f * b
        val lifted = 1f - (1f - y) * (1f - tint) / (1f - tint * y).coerceAtLeast(.0001f)
        val a = ((lifted - y) / (1f - y).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
        fun channel(v: Float) = ((v + (1f - v) * a) * 255).toInt().coerceIn(0, 255)
        pixels[i] = (c and (255 shl 24)) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
    val result = Bitmap.createBitmap(pixels, original.width, original.height, Bitmap.Config.ARGB_8888)
    original.recycle()
    return result.asImageBitmap()
}
