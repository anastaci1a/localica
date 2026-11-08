package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/** A control's retained averaging buffer, borrowed by its existing visible caster. No surface registration. */
internal class ControlAverageRecording(private val layer: GraphicsLayer) {
    fun DrawScope.record(source: GraphicsLayer, effect: RenderEffect?, opacity: Float, blending: Boolean) {
        layer.renderEffect = effect
        layer.alpha = opacity
        layer.blendMode = if (blending) BlendMode.Plus else BlendMode.SrcOver
        layer.record(Density(density, fontScale), layoutDirection, IntSize(1, 1)) { drawLayer(source) }
    }
    fun DrawScope.draw(bounds: Rect, silhouette: Path) {
        clipPath(silhouette) { translate(bounds.left, bounds.top) {
            scale(bounds.width, bounds.height, Offset.Zero) { drawLayer(layer) }
        } }
    }
}

@Composable internal fun rememberControlAverageRecording(): ControlAverageRecording {
    val graphics = LocalGraphicsContext.current
    val layer = remember(graphics) { graphics.createGraphicsLayer() }
    val recording = remember(layer) { ControlAverageRecording(layer) }
    DisposableEffect(graphics, layer) { onDispose { graphics.releaseGraphicsLayer(layer) } }
    return recording
}
