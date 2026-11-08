package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** One contour buffer per published morph plan; never allocate a path in a presented frame. */
internal class TransportContourBuffer {
    private val path = Path()
    fun draw(scope: DrawScope, color: Color, build: Path.() -> Unit) {
        path.rewind()
        path.build()
        scope.drawPath(path, color)
    }
}

/** A canvas layer is borrowed only inside this drawing; every exit restores its owning canvas. */
internal inline fun DrawScope.ownedTransportLayer(bounds: Rect, blendMode: BlendMode,
    content: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(bounds, Paint().apply { this.blendMode = blendMode })
    try { content() } finally { canvas.restore() }
}
