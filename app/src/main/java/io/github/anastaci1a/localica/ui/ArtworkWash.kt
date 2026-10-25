package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/** Small, static texture: cover coordinates span y=0..1, with color continuation on both ends. */
internal object ArtworkWash {
    // The reveal band needs more than the small palette-analysis thumbnail.
    // Keep the prepared texture bounded; low-frequency blur retains its original density.
    const val WIDTH = 256
    const val HEIGHT = WIDTH * 5 / 2
    const val BLUR_WIDTH = 128
    const val TOP = -0.25f

    fun create(cover: Bitmap): Bitmap {
        fun continuation(width: Int): IntArray {
            // Normalized cover coordinates are stretched back to its actual aspect
            // by the drawing matrix; square preparation bounds extreme aspect ratios.
            val sampled = Bitmap.createScaledBitmap(cover, width, width, true)
            val source = IntArray(width * width)
            sampled.getPixels(source, 0, width, 0, 0, width, width)
            if (sampled !== cover) sampled.recycle()
            return IntArray(width * width * 5 / 2) { i ->
                val x = (i % width + 0.5f) / width
                val y = (i / width + 0.5f) / width + TOP
                // Continue nearby artwork with curved drift, not repeated edge stripes.
                val beyond = max(0f, y - 1f)
                val sourceY = when {
                    y < 0f -> -y * 0.35f
                    y > 1f -> 1f - 0.42f * (1f - exp(-beyond))
                    else -> y
                }
                val sourceX = x + sin(x * PI.toFloat() * 2f + beyond * 1.7f) * min(0.14f, beyond * 0.1f)
                val color = source[(sourceY * width).toInt().coerceIn(0, width - 1) * width +
                    (sourceX * width).toInt().coerceIn(0, width - 1)]
                val a = Color.alpha(color) / 255f
                Color.rgb((Color.red(color) * a + 36 * (1 - a)).toInt(),
                    (Color.green(color) * a + 43 * (1 - a)).toInt(), (Color.blue(color) * a + 52 * (1 - a)).toInt())
            }
        }
        val pixels = continuation(WIDTH)
        // Only the sharp reveal band needs higher density. Keep all twelve blur
        // passes at the original bounded resolution instead of quadrupling work.
        val coarse = continuation(BLUR_WIDTH)
        val levels = listOf(2, 5, 12, 24).map { radius ->
            var blurred = coarse
            repeat(3) { blurred = boxBlur(blurred, BLUR_WIDTH, BLUR_WIDTH * 5 / 2, radius) }
            blurred
        }
        fun blurredPixel(level: Int, x: Int, y: Int): Int {
            val colors = levels[level - 1]
            val sx = (x + .5f) * BLUR_WIDTH / WIDTH - .5f
            val sy = (y + .5f) * BLUR_WIDTH / WIDTH - .5f
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val fx = sx - x0; val fy = sy - y0
            fun at(dx: Int, dy: Int) = colors[(y0 + dy).coerceIn(0, BLUR_WIDTH * 5 / 2 - 1) * BLUR_WIDTH +
                (x0 + dx).coerceIn(0, BLUR_WIDTH - 1)]
            val a = at(0, 0); val b = at(1, 0); val c = at(0, 1); val d = at(1, 1)
            fun channel(shift: Int): Int {
                fun value(color: Int) = (color ushr shift and 255).toFloat()
                val top = value(a) + (value(b) - value(a)) * fx
                val bottom = value(c) + (value(d) - value(c)) * fx
                return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            }
            return Color.rgb(channel(16), channel(8), channel(0))
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
            val c1 = if (low == 0) pixels[i] else blurredPixel(low, i % WIDTH, i / WIDTH)
            val c2 = if (high == 0) pixels[i] else blurredPixel(high, i % WIDTH, i / WIDTH)
            fun channel(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
            Color.rgb(channel(Color.red(c1), Color.red(c2)), channel(Color.green(c1), Color.green(c2)),
                channel(Color.blue(c1), Color.blue(c2)))
        }
        return Bitmap.createBitmap(output, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /** Preserve browsing collection treatment; player contrast is applied at display time. */
    fun collectionShade(wash: Bitmap): Bitmap {
        val pixels = IntArray(wash.width * wash.height)
        wash.getPixels(pixels, 0, wash.width, 0, 0, wash.width, wash.height)
        for (i in pixels.indices) {
            val y = (i / wash.width + .5f) / WIDTH + TOP
            val shade = 1f - .48f * smooth((y - .65f) / 1.25f)
            val c = pixels[i]
            pixels[i] = Color.rgb((Color.red(c) * shade).roundToInt(), (Color.green(c) * shade).roundToInt(),
                (Color.blue(c) * shade).roundToInt())
        }
        return Bitmap.createBitmap(pixels, wash.width, wash.height, Bitmap.Config.ARGB_8888)
    }

    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

}
