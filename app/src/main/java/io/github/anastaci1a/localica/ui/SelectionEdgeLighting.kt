package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil
import kotlin.math.floor

/** Legacy HWUI path strokes can leave coverage speckles inside a clear lens. Rasterize
 * only its lighting, with transparent interior pixels; never sample/read back content. */
internal class SelectionEdgeLighting {
    private var previous = emptyList<SelectionRegion>()
    private var previousSize = Size.Zero
    private var previousDensity = 0f
    private var image: ImageBitmap? = null
    var origin = Offset.Zero
        private set

    fun image(regions: List<SelectionRegion>, path: Path, size: Size, density: Float): ImageBitmap? {
        if (regions == previous && size == previousSize && density == previousDensity) return image
        previous = regions; previousSize = size; previousDensity = density
        val bounds = path.getBounds().inflate(density).intersect(Rect(0f, 0f, size.width, size.height))
        if (bounds.isEmpty) { image = null; return null }
        origin = Offset(floor(bounds.left), floor(bounds.top))
        val bitmap = Bitmap.createBitmap(ceil(bounds.right - origin.x).toInt().coerceAtLeast(1),
            ceil(bounds.bottom - origin.y).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-origin.x, -origin.y)
        val outline = path.asAndroidPath()
        val strength = regions.maxOf { it.progress }
        fun white(alpha: Float) = android.graphics.Color.argb((255 * alpha * strength).toInt(), 255, 255, 255)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            shader = LinearGradient(0f, 0f, size.width, size.height,
                intArrayOf(white(.025f), android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb((255 * .018f * strength).toInt(), 0, 0, 0)), null, Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.clipPath(outline)
        for (i in 12 downTo 1) {
            paint.strokeWidth = i * 3 * density
            canvas.drawPath(outline, paint)
        }
        canvas.restore()
        paint.strokeWidth = .7f * density
        paint.shader = LinearGradient(0f, 0f, size.width, size.height,
            intArrayOf(white(.65f), white(.08f), white(.25f)), null, Shader.TileMode.CLAMP)
        canvas.drawPath(outline, paint)
        image = bitmap.asImageBitmap()
        return image
    }
}
