package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor

/** A current publication's already-owned software raster, never a new readback or bitmap copy.
 * Legacy coordinates follow the current recording; pixels remain its latest completed raster,
 * exactly as in ordinary legacy sampling. This does not certify current native/compositor pixels.
 */
internal class MaterialAverageRaster private constructor(
    private val source: FrostedBackdrop,
    private val snapshot: ImageBitmap,
    private val image: ImageBitmap,
    val publication: Int,
) {
    fun isCurrent(): Boolean = source.materialOnly && source.recorded && !source.layer.isReleased &&
        source.snapshot === snapshot && source.fallback === image && source.snapshotReadbackRequest == publication

    fun color(coverage: MaterialAverageCoverage, sourceSize: IntSize): Color? {
        if (!isCurrent() || coverage.status != MaterialAverageCoverageStatus.SAMPLED ||
            sourceSize.width <= 0 || sourceSize.height <= 0) return null
        val bitmap = image.asAndroidBitmap()
        // scatterSnapshot publishes this exact software format. Never read arbitrary/hardware images.
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888) return null
        var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
        fun accumulate(pixel: Int, weight: Float) {
            val a = (pixel ushr 24) / 255f * weight
            red += (pixel ushr 16 and 255) / 255f * a
            green += (pixel ushr 8 and 255) / 255f * a
            blue += (pixel and 255) / 255f * a
            alpha += a
        }
        for (sample in coverage.samples) if (sample.valid) {
            val x = (sample.sourcePosition.x / sourceSize.width * bitmap.width - .5f).coerceIn(0f, (bitmap.width - 1).toFloat())
            val y = (sample.sourcePosition.y / sourceSize.height * bitmap.height - .5f).coerceIn(0f, (bitmap.height - 1).toFloat())
            val left = floor(x).toInt(); val top = floor(y).toInt()
            val right = (left + 1).coerceAtMost(bitmap.width - 1)
            val bottom = (top + 1).coerceAtMost(bitmap.height - 1)
            val fx = x - left; val fy = y - top
            accumulate(bitmap.getPixel(left, top), (1f - fx) * (1f - fy))
            accumulate(bitmap.getPixel(right, top), fx * (1f - fy))
            accumulate(bitmap.getPixel(left, bottom), (1f - fx) * fy)
            accumulate(bitmap.getPixel(right, bottom), fx * fy)
        }
        if (!isCurrent()) return null
        return if (alpha <= 0f) Color.Transparent else Color(
            (red / alpha).coerceIn(0f, 1f), (green / alpha).coerceIn(0f, 1f),
            (blue / alpha).coerceIn(0f, 1f), (alpha / coverage.validCount).coerceIn(0f, 1f))
    }

    companion object {
        fun current(source: FrostedBackdrop?): MaterialAverageRaster? {
            if (source == null || !source.materialOnly || !source.recorded || source.layer.isReleased) return null
            val snapshot = source.snapshot ?: return null
            val image = source.fallback ?: return null
            val publication = source.snapshotReadbackRequest
            if (publication < 0) return null
            return MaterialAverageRaster(source, snapshot, image, publication)
        }
    }
}
