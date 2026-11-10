package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** One mounted transport face owns its contours and the path receiving their interpolation. */
internal class TransportOutline(
    leftFrom: List<Offset>, leftTo: List<Offset>,
    rightFrom: List<Offset>, rightTo: List<Offset>,
) {
    private val pieces = listOf(leftFrom.toList() to leftTo.toList(), rightFrom.toList() to rightTo.toList())
    private val path by lazy { Path() }
    private var progress = Float.NaN
    private var extent = Size.Unspecified

    fun DrawScope.draw(progress: Float, color: Color) {
        if (this@TransportOutline.progress != progress || extent != size) {
            path.reset()
            // Shared winding unions the two pieces without an anti-aliased seam.
            for ((from, to) in pieces) {
                for (i in from.indices) {
                    val p = from[i] + (to[i] - from[i]) * progress
                    if (i == 0) path.moveTo(p.x * size.width, p.y * size.height)
                    else path.lineTo(p.x * size.width, p.y * size.height)
                }
                path.close()
            }
            this@TransportOutline.progress = progress
            extent = size
        }
        drawPath(path, color)
    }
}

/** The layer and destination-in mask belong to the same operation, bounded by the icon face.
 * At an integral cycle the rest wedges stay inside the opaque mask, so no layer is needed.
 */
internal inline fun DrawScope.drawTransportTaper(phase: Float, band: Float, content: DrawScope.() -> Unit) {
    if (phase == 0f) return content()
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    content()
    drawRect(Brush.horizontalGradient(0f to Color.Transparent, band to Color.Black,
        1f - band to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    canvas.restore()
}
