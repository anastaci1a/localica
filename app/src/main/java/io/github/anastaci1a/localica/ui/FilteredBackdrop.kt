package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Filter only a bounded low-resolution backing; sharp foregrounds never enter this pass. */
@Composable
internal fun FilteredBackdrop(modifier: Modifier, source: FrostedBackdrop, origin: () -> Offset,
    resolution: Float, padding: Float = 0f, effect: (Size, Float) -> RenderEffect?) {
    val layer = rememberGraphicsLayer()
    layer.clip = true
    Canvas(modifier) {
        if (source.layer.isReleased || source.layer.size.width <= 0) return@Canvas
        val r = resolution.coerceIn(.25f, 1f)
        val extent = IntSize(ceil((size.width + 2 * padding) * r).toInt().coerceAtLeast(1),
            ceil((size.height + 2 * padding) * r).toInt().coerceAtLeast(1))
        layer.renderEffect = effect(Size(extent.width.toFloat(), extent.height.toFloat()), r)
        layer.record(size = extent) {
            clipRect { scale(r, r, Offset.Zero) {
                val delta = source.origin - origin() + Offset(padding, padding)
                translate(delta.x, delta.y) { drawLayer(source.layer) }
            } }
        }
        translate(-padding, -padding) { scale(1f / r, 1f / r, Offset.Zero) { drawLayer(layer) } }
    }
}
