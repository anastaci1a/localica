package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import io.github.anastaci1a.localica.ui.ArtworkWash
import java.util.Random

/** Generated #711 fixtures (never the operator's cover): bordered covers and borderless controls, 256 px square. */
internal object BackdropEdgeCovers {
    const val SIZE = 256

    class Cover(val name: String, val bitmap: Bitmap, val frame: Int?, val thickness: Int, val interiorLuminance: Float)

    private fun cover(seed: Long, pixel: (Random, Int, Int) -> Int): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(SIZE * SIZE) { pixel(random, it % SIZE, it / SIZE) }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun gray(value: Int) = Color.rgb(value, value, value)

    private fun framed(name: String, seed: Long, frame: Int, thickness: Int, interior: (Random, Int, Int) -> Int): Cover {
        val bitmap = cover(seed) { random, x, y ->
            if (x < thickness || y < thickness || x >= SIZE - thickness || y >= SIZE - thickness) frame else interior(random, x, y)
        }
        var sum = 0.0; var count = 0
        val inset = thickness + SIZE / 10
        for (y in inset until SIZE - inset) for (x in inset until SIZE - inset) { sum += luminance(bitmap.getPixel(x, y)); count++ }
        return Cover(name, bitmap, frame, thickness, (sum / count).toFloat())
    }

    /** White art in a solid black frame (the reported shape), light grain inside. */
    fun blackFrame() = framed("black-frame", 1, Color.BLACK, 10) { r, _, _ -> gray(236 + r.nextInt(20)) }

    /** The reported layout: black frame, white art, a saturated tab touching the frame's inner edge. */
    fun talkMemoryLike() = framed("black-frame-tab", 2, Color.BLACK, 8) { r, x, y ->
        if (x >= 204 && y in 90..170) Color.rgb(8, 160, 240) else if (y in 118..130 && x in 90..170 && r.nextInt(3) == 0) gray(30)
        else gray(238 + r.nextInt(18))
    }

    /** Dark art in a white frame: the inverse polarity. */
    fun whiteFrame() = framed("white-frame", 3, Color.WHITE, 12) { r, _, _ -> Color.rgb(18 + r.nextInt(20), 26 + r.nextInt(20), 70 + r.nextInt(30)) }

    /** A thin (4 px, 1.6%) dark border around pale art. */
    fun thinBorder() = framed("thin-border", 4, Color.rgb(110, 20, 32), 4) { r, _, _ -> Color.rgb(246 + r.nextInt(10), 236 + r.nextInt(12), 196 + r.nextInt(16)) }

    fun bordered() = listOf(blackFrame(), talkMemoryLike(), whiteFrame(), thinBorder())

    /** Photo-like: smooth diagonal colour field with grain to every edge. */
    fun borderlessPhoto() = Cover("borderless-photo", cover(11) { r, x, y ->
        Color.rgb((40 + x * 180 / SIZE + r.nextInt(16)).coerceAtMost(255), (60 + y * 150 / SIZE + r.nextInt(16)).coerceAtMost(255),
            (200 - (x + y) * 140 / (2 * SIZE) + r.nextInt(16)).coerceIn(0, 255))
    }, null, 0, 0f)

    /** High-detail content touching every edge (random colour stripes). */
    fun borderlessStripes(): Cover {
        val colors = Random(12).let { r -> IntArray(32) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) } }
        return Cover("borderless-stripes", cover(13) { r, x, y -> if (r.nextInt(9) == 0) gray(r.nextInt(256)) else colors[(x + y / 3) / 8 % 32] }, null, 0, 0f)
    }

    /** A uniform sky over the top 40% and dense ground below: a uniform edge region that is not a frame. */
    fun borderlessSky() = Cover("borderless-sky", cover(14) { r, _, y ->
        if (y < SIZE * 2 / 5) Color.rgb(96, 150, 220) else Color.rgb(30 + r.nextInt(60), 90 + r.nextInt(80), 30 + r.nextInt(40))
    }, null, 0, 0f)

    /** Light art fading to a dark vignette at the edges, never uniform there. */
    fun borderlessVignette() = Cover("borderless-vignette", cover(15) { r, x, y ->
        val dx = (x - SIZE / 2f) / (SIZE / 2f); val dy = (y - SIZE / 2f) / (SIZE / 2f)
        val v = (230 - 170 * (dx * dx + dy * dy) / 2f).toInt() + r.nextInt(24) - 12
        gray(v.coerceIn(0, 255))
    }, null, 0, 0f)

    fun borderless() = listOf(borderlessPhoto(), borderlessStripes(), borderlessSky(), borderlessVignette())

    fun luminance(color: Int) = (.2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)) / 255f

    /** Mean luminance of the wash row at cover-relative y (0 = cover top, 1 = cover bottom), over the central 80% of x. */
    fun washRow(wash: Bitmap, coverY: Float): Float {
        val row = ((coverY - ArtworkWash.TOP) * ArtworkWash.WIDTH).toInt().coerceIn(0, wash.height - 1)
        var sum = 0f; var count = 0
        for (x in wash.width / 10 until wash.width * 9 / 10) { sum += luminance(wash.getPixel(x, row)); count++ }
        return sum / count
    }
}
