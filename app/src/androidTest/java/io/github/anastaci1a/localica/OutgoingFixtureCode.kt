package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray

/** Fixture identity, independent of blue/neutral backing and flat material tint. */
internal object OutgoingFixtureCode {
    data class Frame(val pixels: Int, val peak: Int, val rows: Int, val bounds: JSONArray)
    private data class Run(val start: Int, val end: Int, val sign: Int, val mean: Float) {
        val width get() = end - start
    }
    fun measure(bitmap: Bitmap, minimumContrast: Int): Frame {
        var pixels = 0; var peak = 0; var rows = 0
        var left = bitmap.width; var top = bitmap.height; var right = 0; var bottom = 0
        for (y in 0 until bitmap.height step 2) {
            val runs = mutableListOf<Run>()
            var start = 0; var sign = 0; var sum = 0; var samples = 0
            fun finish(end: Int) {
                if (sign != 0 && end - start >= 8 && samples > 0)
                    runs += Run(start, end, sign, sum.toFloat() / samples)
            }
            for (x in 0..bitmap.width step 2) {
                val c = if (x < bitmap.width) bitmap.getPixel(x, y) else 0
                val d = if (x < bitmap.width) Color.red(c) - Color.green(c) else 0
                val next = if (d > 2) 1 else if (d < -2) -1 else 0
                if (next != sign) { finish(x); start = x; sign = next; sum = 0; samples = 0 }
                sum += d; samples++
            }
            for (i in 0..runs.size - 4) {
                val code = runs.subList(i, i + 4)
                if (code.map { it.sign } != listOf(1, -1, 1, -1)) continue
                if (code.zipWithNext().any { (a, b) -> b.start - a.end > 8 }) continue
                if (code.maxOf { it.width } > code.minOf { it.width } * 1.6f) continue
                val contrast = code.zipWithNext().minOf { (a, b) -> kotlin.math.abs(a.mean - b.mean) }.toInt()
                if (contrast <= minimumContrast) continue
                pixels += code.sumOf { it.width / 2 }; rows++; peak = maxOf(peak, contrast)
                left = minOf(left, code.first().start); right = maxOf(right, code.last().end)
                top = minOf(top, y); bottom = maxOf(bottom, y + 2)
                break
            }
        }
        return Frame(pixels, peak, rows, JSONArray(listOf(left, top, right, bottom)))
    }
}
