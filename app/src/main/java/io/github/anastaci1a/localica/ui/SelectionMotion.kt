package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** The same ease-out applies to arrivals, departures and interrupted reversals. */
internal fun selectionMotionSpec(reducedMotion: Boolean, duration: Int = 180) =
    tween<Float>(if (reducedMotion) 0 else duration, easing = LinearOutSlowInEasing)

internal fun selectionRevealBounds(bounds: Rect, visibility: Float): Rect {
    val scale = .92f + .08f * visibility.coerceIn(0f, 1f)
    val inset = bounds.size * ((1f - scale) / 2f)
    return Rect(bounds.left + inset.width, bounds.top + inset.height,
        bounds.right - inset.width, bounds.bottom - inset.height)
}

/** Overlap uses the greatest per-entry visibility, never additive opacity. The selected
 * scene already owns silhouette antialiasing, so this mask deliberately has no lens clip. */
internal fun DrawScope.drawSelectionVisibilityMask(entries: List<Pair<Rect, Float>>, gap: Float) {
    entries.sortedBy { it.second }.forEach { (bounds, alpha) ->
        val r = bounds.inflate(gap / 2f)
        drawRect(Color.White.copy(alpha = alpha.coerceIn(0f, 1f)), r.topLeft, r.size, blendMode = BlendMode.Src)
    }
}

/** Premultiplied scene mixing: B * (1 - mask) + selected * mask, including alpha.
 * Ordinary SrcOver would double translucent foreground during lens appearance.
 * All extra recordings are used only while an entry is arriving/departing.
 */
internal fun DrawScope.drawSelectionTransition(base: GraphicsLayer, selected: GraphicsLayer, mask: GraphicsLayer) {
    val canvas = drawContext.canvas
    val bounds = Rect(Offset.Zero, size)
    canvas.saveLayer(bounds, Paint())
    drawLayer(base)
    canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstOut })
    drawLayer(mask)
    canvas.restore()
    canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.Plus })
    drawLayer(selected)
    canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
    drawLayer(mask)
    canvas.restore()
    canvas.restore()
    canvas.restore()
}
