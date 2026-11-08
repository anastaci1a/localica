package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.WeakHashMap

/** Actual rendered alpha, including vector transforms, strokes and raster transparency. Viewport
 * padding is excluded. Weak keys retain neither old packs nor their painters after a pack switch.
 */
internal object SkipInk {
    private val bounds = WeakHashMap<Any, Rect>()
    @Synchronized fun measure(key: Any, draw: DrawScope.() -> Unit): Rect = bounds.getOrPut(key) {
        val resolution = 512
        val image = ImageBitmap(resolution, resolution)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image),
                Size(resolution.toFloat(), resolution.toFloat()), draw)
            val pixels = image.toPixelMap()
            var left = resolution
            var right = 0
            var top = resolution
            var bottom = 0
            for (y in 0 until resolution) for (x in 0 until resolution) if (pixels[x,y].alpha > 0f) {
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
            }
            if (right <= left) Rect(0f, 0f, 1f, 1f)
            else Rect(left / resolution.toFloat(), top / resolution.toFloat(),
                right / resolution.toFloat(), bottom / resolution.toFloat())
        } finally {
            image.asAndroidBitmap().recycle()
        }
    }
}
