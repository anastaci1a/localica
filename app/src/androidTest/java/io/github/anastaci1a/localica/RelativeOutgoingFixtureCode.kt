package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** Proposed diagnostic only: four relative chroma plateaus against their adjacent material backing.
 * Mirrors oracle-review/outgoing/revision3/relative-chroma.mjs; native qualification remains required. */
internal object RelativeOutgoingFixtureCode {
    data class Row(val y: Int, val start: Int, val end: Int, val backing: Int,
        val means: List<Double>, val amplitude: Double)
    data class Frame(val pixels: Int, val peak: Double, val rows: Int, val strongest: Row?)
    private data class Edge(var i: Int, var jump: Int)

    fun measure(bitmap: Bitmap, noiseFloor: Int = 2, minimumRows: Int = 4): Frame =
        measure(bitmap.width, bitmap.height, noiseFloor, minimumRows) { x, y ->
            val color = bitmap.getPixel(x, y)
            Color.red(color) - Color.green(color)
        }

    fun measure(width: Int, height: Int, noiseFloor: Int = 2, minimumRows: Int = 4,
        chroma: (Int, Int) -> Int): Frame {
        var pixels = 0
        var peak = 0.0
        val rows = mutableListOf<Row>()
        for (y in 0 until height step 2) {
            val d = (0 until width step 2).map { x -> chroma(x, y) }
            val edges = mutableListOf<Edge>()
            for (i in 1 until d.size) {
                val jump = d[i] - d[i - 1]
                if (abs(jump) < noiseFloor) continue
                val previous = edges.lastOrNull()
                if (previous != null && i - previous.i <= 2 && jump.sign == previous.jump.sign) {
                    if (abs(jump) > abs(previous.jump)) { previous.i = i; previous.jump = jump }
                } else edges += Edge(i, jump)
            }
            var best: Row? = null
            for (i in 0 until (edges.size - 2).coerceAtLeast(0)) {
                val a = edges[i]; val b = edges[i + 1]; val c = edges[i + 2]
                if (!(a.jump < 0 && b.jump > 0 && c.jump < 0)) continue
                val widths = listOf(b.i - a.i, c.i - b.i)
                if (widths.min() * 2 < 8 || widths.max() > widths.min() * 1.6) continue
                // JavaScript Math.round on these positive half-integers, rather than ties-to-even.
                val band = floor((widths[0] + widths[1]) / 2.0 + .5).toInt()
                val start = a.i - band; val end = c.i + band
                if (start < 0 || end + 3 >= d.size) continue
                fun average(left: Int, right: Int): Double {
                    val values = d.subList(left + 1, right - 1)
                    return if (values.isEmpty()) Double.NaN else values.sum().toDouble() / values.size
                }
                val means = listOf(average(start, a.i), average(a.i, b.i), average(b.i, c.i), average(c.i, end))
                if (means.any { !it.isFinite() }) continue
                val outside = d.subList(end + 1, minOf(d.size, end + 1 + maxOf(3, band / 2))).sorted()
                val backing = outside[outside.size / 2]
                if (!(means[0] > backing && means[1] < backing && means[2] > backing && means[3] < backing)) continue
                val amplitude = minOf(means[0] - means[1], means[2] - means[1], means[2] - means[3])
                if (amplitude < noiseFloor) continue
                if (abs(means[0] - means[2]) > amplitude / 2 || abs(means[1] - means[3]) > amplitude / 2) continue
                val row = Row(y, start * 2, end * 2, backing, means, amplitude)
                if (best == null || amplitude > best.amplitude) best = row
            }
            best?.let {
                pixels += (it.end - it.start) / 2
                peak = maxOf(peak, it.amplitude)
                rows += it
            }
        }
        val enough = rows.size >= minimumRows
        return Frame(if (enough) pixels else 0, if (enough) peak else 0.0, rows.size,
            rows.fold<Row, Row?>(null) { best, row -> if (best == null || row.amplitude > best.amplitude) row else best })
    }
}
