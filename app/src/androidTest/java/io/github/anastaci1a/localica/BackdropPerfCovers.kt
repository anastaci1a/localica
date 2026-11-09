package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Random
import kotlin.math.*

/** Shared deterministic #691 performance inputs. Exported images stay outside Git. */
internal object BackdropPerfCovers {
    const val SIZE = 256
    const val VERSION = 1
    data class Cover(val name: String, val classification: String, val bitmap: Bitmap, val seed: Long)

    private fun rgb(r: Double, g: Double, b: Double) = Color.rgb(r.roundToInt().coerceIn(0, 255),
        g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
    private fun field(name: String, seed: Long, draw: (Double, Double, Double) -> Int): Cover {
        val random = Random(seed)
        val pixels = IntArray(SIZE * SIZE) {
            val x = it % SIZE / 255.0; val y = it / SIZE / 255.0
            draw(x, y, random.nextDouble() * 2 - 1)
        }
        return Cover(name, "Realistic", Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888), seed)
    }
    private fun painted(name: String, seed: Long, color: Int, draw: (Canvas, Paint) -> Unit): Cover {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(color)
        draw(canvas, Paint(Paint.ANTI_ALIAS_FLAG))
        return Cover(name, "Realistic", bitmap, seed)
    }
    fun realistic(): List<Cover> = listOf(
        Cover("photo-grain", "Realistic", BackdropEdgeCovers.borderlessPhoto().bitmap, 11),
        field("dark-moody", 9702) { x, y, n ->
            val glow = exp(-((x - .65).pow(2) + (y - .3).pow(2)) * 13)
            rgb(8 + 26 * glow + n * 3, 12 + 38 * glow + n * 3, 23 + 71 * glow + n * 3)
        },
        field("bright-pastel", 9703) { x, y, n -> rgb(220 + 25 * x + n * 2, 192 + 54 * y + n * 2, 231 - 25 * x + n * 2) },
        field("saturated", 9704) { x, y, n ->
            rgb(128 + 120 * sin(x * PI * 2) + n * 2, 128 + 120 * sin(y * PI * 2) + n * 2,
                128 + 120 * cos((x + y) * PI) + n * 2)
        },
        field("monochrome", 9705) { x, y, n ->
            val v = 36 + x * 110 + y * 90 + n * 8; rgb(v, v, v)
        },
        painted("bold-typography", 9706, Color.rgb(235, 54, 31)) { c, p ->
            p.color = Color.rgb(255, 240, 205); p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            p.textSize = 64f; c.drawText("NIGHT", 10f, 103f, p); c.drawText("SHIFT", 10f, 168f, p)
            p.textSize = 12f; c.drawText("LOCALICA  /  VOLUME 01", 14f, 228f, p)
        },
        painted("busy-collage", 9707, Color.rgb(23, 21, 37)) { c, p ->
            val random = Random(9707)
            repeat(160) {
                p.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                val x = random.nextInt(SIZE).toFloat(); val y = random.nextInt(SIZE).toFloat()
                if (it % 3 == 0) c.drawCircle(x, y, 2f + random.nextInt(32), p)
                else c.drawRect(x, y, x + 1 + random.nextInt(44), y + 1 + random.nextInt(44), p)
            }
        },
        Cover("white-frame", "Realistic", BackdropEdgeCovers.whiteFrame().bitmap, 3),
        Cover("black-frame", "Realistic", BackdropEdgeCovers.blackFrame().bitmap, 1),
        painted("portrait-silhouette", 9710, Color.rgb(224, 178, 130)) { c, p ->
            p.color = Color.rgb(33, 24, 36); c.drawCircle(128f, 88f, 44f, p)
            c.drawOval(52f, 139f, 204f, 315f, p)
            p.color = Color.rgb(238, 205, 159); p.strokeWidth = 3f; c.drawLine(20f, 233f, 236f, 233f, p)
        },
        painted("neon-on-black", 9711, Color.rgb(2, 3, 9)) { c, p ->
            p.style = Paint.Style.STROKE
            val triangle = Path().apply { moveTo(128f, 29f); lineTo(229f, 218f); lineTo(27f, 218f); close() }
            for (w in listOf(17f, 9f, 3f)) {
                p.strokeWidth = w; p.color = Color.argb(if (w > 3) 42 else 255, 245, 25, 194); c.drawPath(triangle, p)
                p.color = Color.argb(if (w > 3) 42 else 255, 21, 229, 245); c.drawCircle(128f, 133f, 58f, p)
            }
        },
        field("earth-landscape", 9712) { x, y, n ->
            val ridge = .46 + .12 * sin(x * PI * 2) + .045 * sin(x * PI * 7)
            if (y < ridge) rgb(154 - 30 * y + n * 3, 178 - 20 * y + n * 3, 173 + 28 * y + n * 3)
            else rgb(96 + 60 * y + n * 6, 88 + 30 * x + n * 5, 48 + 22 * y + n * 4)
        }
    )
    fun adversarial(): List<Cover> {
        fun pixels(name: String, seed: Long, pixel: (Int, Int) -> Int) = Cover(name, "Adversarial",
            Bitmap.createBitmap(IntArray(SIZE * SIZE) { pixel(it % SIZE, it / SIZE) }, SIZE, SIZE, Bitmap.Config.ARGB_8888), seed)
        return listOf(
            pixels("checker-1px", 9801) { x, y -> if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE },
            pixels("stripes-1px", 9802) { x, _ -> if (x % 2 == 0) Color.RED else Color.CYAN },
            pixels("quadrants", 9803) { x, y -> if (y < 128) { if (x < 128) Color.WHITE else Color.BLACK }
                else if (x < 128) Color.MAGENTA else Color.GREEN },
            pixels("low-alpha", 9804) { x, y -> Color.argb(if ((x + y) % 2 == 0) 17 else 83, x, y, 255 - x) },
            pixels("single-line", 9805) { x, _ -> if (x == 127) Color.WHITE else Color.BLACK },
            Cover("thin-border", "Adversarial", BackdropEdgeCovers.thinBorder().bitmap, 4))
    }
}
