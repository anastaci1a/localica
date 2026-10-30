package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** Owner-thread scratch input. The consumer must finish sampling before returning and
 * must not retain the image. A displayed output must own independent pixels. */
internal class SoftwareControlCapture {
    private var image: ImageBitmap? = null
    private var inUse = false

    fun withScene(extent: IntSize, density: Density, direction: LayoutDirection,
        onAllocation: ((LensAllocation) -> Unit)? = null,
        record: DrawScope.() -> Unit, consume: (ImageBitmap) -> Unit) {
        check(!inUse) { "Software control capture cannot be reentered" }
        inUse = true
        try {
            val target = image?.takeIf { it.width == extent.width && it.height == extent.height }
                ?: ImageBitmap(extent.width, extent.height).also {
                    image = it
                    onAllocation?.invoke(LensAllocation.CaptureBitmap)
                }
            target.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
            // Fresh scope/canvas state also isolates a previous failed recording.
            CanvasDrawScope().draw(density, direction, Canvas(target),
                Size(extent.width.toFloat(), extent.height.toFloat()), record)
            consume(target)
        } finally {
            inUse = false
        }
    }
}
