package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/** Small, static texture: cover coordinates span y=0..1, with color continuation on both ends. */
internal object ArtworkWash {
    const val WIDTH = 128
    const val HEIGHT = 320
    const val TOP = -0.25f

    fun create(cover: Bitmap): Bitmap {
        val pixels = IntArray(WIDTH * HEIGHT) { i ->
            val x = (i % WIDTH + 0.5f) / WIDTH
            val y = (i / WIDTH + 0.5f) / WIDTH + TOP
            // Extend the nearby artwork, with broad curved drift instead of repeated edge stripes.
            val beyond = max(0f, y - 1f)
            val sourceY = when {
                y < 0f -> -y * 0.35f
                y > 1f -> 1f - 0.42f * (1f - exp(-beyond))
                else -> y
            }
            val sourceX = x + sin(x * PI.toFloat() * 2f + beyond * 1.7f) * min(0.14f, beyond * 0.1f)
            val color = cover.getPixel((sourceX * cover.width).toInt().coerceIn(0, cover.width - 1),
                (sourceY * cover.height).toInt().coerceIn(0, cover.height - 1))
            // Composite transparent artwork onto a neutral base before filtering.
            val a = Color.alpha(color) / 255f
            Color.rgb((Color.red(color) * a + 36 * (1 - a)).toInt(),
                (Color.green(color) * a + 43 * (1 - a)).toInt(), (Color.blue(color) * a + 52 * (1 - a)).toInt())
        }
        val levels = listOf(pixels) + listOf(2, 5, 12, 24).map { radius ->
            var blurred = pixels
            repeat(3) { blurred = boxBlur(blurred, WIDTH, HEIGHT, radius) }
            blurred
        }
        val output = IntArray(pixels.size) { i ->
            val x = (i % WIDTH + 0.5f) / WIDTH
            val y = (i / WIDTH + 0.5f) / WIDTH + TOP
            val sweep = y + 0.035f * sin(x * PI.toFloat() * 2f + 0.6f)
            val depth = if (y < 0.2f) 1f + 2f * smooth((0.2f - y) / 0.45f)
                else 4f * smooth((sweep - 0.45f) / 1.25f)
            val low = depth.toInt().coerceIn(0, 4)
            val high = min(4, low + 1)
            val t = depth - low
            val c1 = levels[low][i]; val c2 = levels[high][i]
            val shade = 1f - 0.48f * smooth((y - 0.65f) / 1.25f)
            fun channel(a: Int, b: Int) = ((a + (b - a) * t) * shade).roundToInt().coerceIn(0, 255)
            Color.rgb(channel(Color.red(c1), Color.red(c2)), channel(Color.green(c1), Color.green(c2)),
                channel(Color.blue(c1), Color.blue(c2)))
        }
        return Bitmap.createBitmap(output, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

}
