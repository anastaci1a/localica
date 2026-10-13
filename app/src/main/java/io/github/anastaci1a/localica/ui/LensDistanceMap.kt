package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Half
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Raw half-float geometry, never color-managed: signed-distance data is not RGB. */
internal class LensDistanceMap(val bitmap: Bitmap, val origin: Offset, val scale: Float, val range: Float,
    private val distances: FloatArray) {
    fun sample(point: Offset): LensField {
        val p = (point - origin) * scale
        fun d(x: Int, y: Int) = distances[y.coerceIn(0, bitmap.height - 1) * bitmap.width + x.coerceIn(0, bitmap.width - 1)]
        val x = floor(p.x).toInt(); val y = floor(p.y).toInt()
        val fx = p.x - x; val fy = p.y - y
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        val distance = mix(mix(d(x, y), d(x + 1, y), fx), mix(d(x, y + 1), d(x + 1, y + 1), fx), fy)
        return LensField(max(distance - .5f, 0f) / scale,
            Offset(((d(x + 1, y) - d(x - 1, y)) * .5f).coerceIn(-1f, 1f),
                ((d(x, y + 1) - d(x, y - 1)) * .5f).coerceIn(-1f, 1f)))
    }
    companion object {
        fun create(path: Path): LensDistanceMap {
            val bounds = path.getBounds()
            val scale = .5f
            val origin = Offset(floor(bounds.left) - 4, floor(bounds.top) - 4)
            val width = ceil((bounds.right - origin.x + 4) * scale).toInt().coerceAtLeast(1)
            val height = ceil((bounds.bottom - origin.y + 4) * scale).toInt().coerceAtLeast(1)
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(mask).apply {
                scale(scale, scale); translate(-origin.x, -origin.y)
                drawPath(path.asAndroidPath(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            }
            val pixels = IntArray(width * height)
            mask.getPixels(pixels, 0, width, 0, 0, width, height)
            mask.recycle()
            val distances = selectionDistances(pixels, width, height)
            val range = max(bounds.width, bounds.height).coerceAtLeast(1f)
            fun d(x: Int, y: Int) = distances[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
            val buffer = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
            for (y in 0 until height) for (x in 0 until width) {
                val gx = ((d(x + 1, y) - d(x - 1, y)) * .5f).coerceIn(-1f, 1f)
                val gy = ((d(x, y + 1) - d(x, y - 1)) * .5f).coerceIn(-1f, 1f)
                buffer.putHalf(max(d(x, y) - .5f, 0f) / (scale * range))
                buffer.putHalf((gx + 1) * .5f)
                buffer.putHalf((gy + 1) * .5f)
                buffer.putHalf(1f)
            }
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
            bitmap.copyPixelsFromBuffer(buffer)
            return LensDistanceMap(bitmap, origin, scale, range, distances)
        }
    }
}

// Serialize binary16 bits, without numeric widening. Lint mistakes putShort's
// fluent ByteBuffer return type for a conversion of the half-float argument.
@android.annotation.SuppressLint("HalfFloat")
private fun ByteBuffer.putHalf(value: Float) { putShort(Half.toHalf(value)) }
