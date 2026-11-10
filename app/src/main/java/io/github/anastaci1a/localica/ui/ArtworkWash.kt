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
        fun scaledSource(width: Int): IntArray {
            // Normalized cover coordinates are stretched back to its actual aspect
            // by the drawing matrix; square preparation bounds extreme aspect ratios.
            val sampled = Bitmap.createScaledBitmap(cover, width, width, true)
            val source = IntArray(width * width)
            sampled.getPixels(source, 0, width, 0, 0, width, width)
            if (sampled !== cover) sampled.recycle()
            return source
        }
        val sharpSource = scaledSource(WIDTH)
        // A frame (#711) is found once, at preparation; borderless covers find none and keep the previous wash exactly.
        val topBand = edgeBand(sharpSource, WIDTH, bottom = false)
        val bottomBand = edgeBand(sharpSource, WIDTH, bottom = true)
        fun continuation(width: Int, source: IntArray): IntArray {
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
                val opaque = opaque(color)
                // Beyond the cover, a frame's tone leads and the drifting interior continuation takes over further out.
                val band = if (y < 0f) topBand else if (y > 1f) bottomBand else null
                if (band == null) opaque else mix(opaque, band.color, band.strength * edgeWeight(if (y < 0f) -y else beyond))
            }
        }
        val pixels = continuation(WIDTH, sharpSource)
        // Only the sharp reveal band needs higher density. Keep all twelve blur
        // passes at the original bounded resolution instead of quadrupling work.
        val coarse = continuation(BLUR_WIDTH, scaledSource(BLUR_WIDTH))
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
            val depth = blurDepth(x, y)
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

    /** Resolve the top continuation smoothly into sharp artwork. The previous branch at
     * y=.2 jumped from blur level1 directly to0, producing a horizontal crease on cover borders.
     * Keep the existing upper/lower treatment; only add the missing .2..4 reveal transition. */
    internal fun blurDepth(x: Float, y: Float): Float {
        val sweep = y + 0.035f * sin(x * PI.toFloat() * 2f + 0.6f)
        val top = if (y < .2f) 1f + 2f * smooth((.2f - y) / .45f)
            else 1f - smooth((y - .2f) / .2f)
        return max(top, 4f * smooth((sweep - .45f) / 1.25f))
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

    /** A cover's outer frame on one side (#711): its opaque colour and how strongly it should lead the continuation. */
    internal data class EdgeBand(val color: Int, val thickness: Int, val strength: Float)

    /** Transparent pixels continue over the same neutral base as before, with the same arithmetic. */
    internal fun opaque(color: Int): Int {
        val a = (color ushr 24) / 255f
        return rgb(((color shr 16 and 255) * a + 36 * (1 - a)).toInt(),
            ((color shr 8 and 255) * a + 43 * (1 - a)).toInt(), ((color and 255) * a + 52 * (1 - a)).toInt())
    }

    /** Find a solid frame along the top or bottom edge of a square [width] sample: consecutive near-uniform rows
     * from the edge, thin enough to be a border (not a sky or plain background) and distinct from the art inside it.
     * Returns null for borderless covers, which then keep the previous continuation exactly. */
    internal fun edgeBand(source: IntArray, width: Int, bottom: Boolean): EdgeBand? {
        val height = source.size / width
        if (width < 16 || height < 16) return null
        fun pixel(row: Int, x: Int) = opaque(source[(if (bottom) height - 1 - row else row) * width + x])
        fun distance(a: Int, b: Int) = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
            abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
        fun mean(from: Int, to: Int): Int {
            var r = 0L; var g = 0L; var b = 0L
            for (row in from until to) for (x in 0 until width) {
                val c = pixel(row, x); r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
            }
            val n = ((to - from) * width).toLong()
            return rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        val reference = mean(0, 1)
        val maxThickness = (height * MAX_FRAME).toInt()
        var thickness = 0
        while (thickness <= maxThickness && thickness < height) {
            var matching = 0
            for (x in 0 until width) if (distance(pixel(thickness, x), reference) <= FRAME_TOLERANCE) matching++
            if (matching < width * FRAME_COVERAGE) break
            thickness++
        }
        // Too thin to be a frame, or a uniform region too deep to be one (a sky, a plain background).
        if (thickness < maxOf(2, (height * MIN_FRAME).toInt()) || thickness > maxThickness) return null
        val color = mean(0, thickness)
        val inner = mean(thickness, minOf(height, thickness + (height * .12f).toInt().coerceAtLeast(1)))
        val strength = smooth((distance(color, inner) - 24f) / 56f)
        return if (strength > 0f) EdgeBand(color, thickness, strength) else null
    }

    /** How much a frame leads at [distance] cover heights beyond its edge: fully at the edge, easing to just under
     * half by the far end of the continuation so the interior colours return gradually, never as a seam. */
    internal fun edgeWeight(distance: Float): Float = 1f - .55f * smooth(distance / 1.25f)

    private fun mix(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = from shr shift and 255; val b = to shr shift and 255
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return rgb(channel(16), channel(8), channel(0))
    }

    /** android.graphics.Color.rgb for in-range channels, usable from JVM unit tests. */
    private fun rgb(red: Int, green: Int, blue: Int): Int = (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private const val MIN_FRAME = .008f
    private const val MAX_FRAME = .16f
    private const val FRAME_TOLERANCE = 28
    private const val FRAME_COVERAGE = .9f
}

