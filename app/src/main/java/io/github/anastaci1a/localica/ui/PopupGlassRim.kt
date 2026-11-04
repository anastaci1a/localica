package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/** Popup corners use their own surface normals. A sweep about the panel center
 * stretches diagonal glints onto straight walls as the panel changes aspect. */
internal class PopupGlassRimCache {
    private var bounds: Rect? = null
    private var radius = Float.NaN
    private var tone: GlassTone? = null
    private var frame: PopupGlassRimFrame? = null

    fun frame(bounds: Rect, radius: Float, tone: GlassTone): PopupGlassRimFrame {
        val r = radius.coerceIn(0f, minOf(bounds.width, bounds.height) / 2f)
        if (this.bounds == bounds && this.radius == r && this.tone == tone) return requireNotNull(frame)
        val l = bounds.left; val t = bounds.top; val right = bounds.right; val bottom = bounds.bottom
        val outline = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(r))) }
        val walls = Path().apply {
            moveTo(l + r, t); lineTo(right - r, t)
            moveTo(right, t + r); lineTo(right, bottom - r)
            moveTo(right - r, bottom); lineTo(l + r, bottom)
            moveTo(l, bottom - r); lineTo(l, t + r)
        }
        val corners = if (r == 0f) emptyList() else listOf(
            Offset(l + r, t + r) to 180f,
            Offset(right - r, t + r) to 270f,
            Offset(right - r, bottom - r) to 0f,
            Offset(l + r, bottom - r) to 90f,
        ).map { (center, start) ->
            val arc = Path().apply {
                arcTo(Rect(center.x - r, center.y - r, center.x + r, center.y + r), start, 90f, true)
            }
            arc to glassRimBrushes(center, tone)
        }
        return PopupGlassRimFrame(outline, walls, tone.color(Color.White).copy(alpha = .025f), corners).also {
            this.bounds = bounds; this.radius = r; this.tone = tone; frame = it
        }
    }
}

internal data class PopupGlassRimFrame(
    val outline: Path, val walls: Path, val wallShine: Color,
    val corners: List<Pair<Path, GlassRimBrushes>>,
)

/** Same inward stroke widths, corner glints and shade as glass controls. */
internal fun DrawScope.drawPopupGlassRim(bounds: Rect, radius: Float, tone: GlassTone,
    opacity: Float, cache: PopupGlassRimCache) {
    if (opacity <= 0f) return
    val frame = cache.frame(bounds, radius, tone)
    clipPath(frame.outline) {
        val shine = Stroke(2.6.dp.toPx())
        val shade = Stroke(1.4.dp.toPx())
        drawPath(frame.walls, frame.wallShine, alpha = opacity, style = shine)
        frame.corners.forEach { (arc, brushes) ->
            drawPath(arc, brushes.shine, alpha = opacity, style = shine)
            drawPath(arc, brushes.shade, alpha = opacity, style = shade)
        }
    }
}
