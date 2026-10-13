package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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
    resolution: Float, padding: Float = 0f, homeSource: FrostedBackdrop? = null,
    homeWeight: () -> Float = { 0f }, effect: (Size, Float) -> RenderEffect?) {
    val layer = rememberGraphicsLayer()
    val primary = if (homeSource != null && homeSource !== source) rememberGraphicsLayer() else null
    val home = if (primary != null) rememberGraphicsLayer() else null
    layer.clip = true
    // Isolate unchanged filters from ancestor recordings; source RenderNode changes
    // still invalidate the sampled result, while sibling motion reuses this drawing.
    Canvas(modifier.graphicsLayer()) {
        if (source.layer.isReleased || source.layer.size.width <= 0) return@Canvas
        val r = resolution.coerceIn(.25f, 1f)
        val extent = IntSize(ceil((size.width + 2 * padding) * r).toInt().coerceAtLeast(1),
            ceil((size.height + 2 * padding) * r).toInt().coerceAtLeast(1))
        layer.renderEffect = effect(Size(extent.width.toFloat(), extent.height.toFloat()), r)
        fun androidx.compose.ui.graphics.drawscope.DrawScope.scene(backing: FrostedBackdrop) {
            clipRect { scale(r, r, Offset.Zero) {
                val delta = backing.origin - origin() + Offset(padding, padding)
                translate(delta.x, delta.y) { drawLayer(backing.layer) }
            } }
        }
        val blend = if (homeSource != null && !homeSource.layer.isReleased && homeSource.layer.size.width > 0)
            homeWeight().coerceIn(0f, 1f) else 0f
        if (primary != null && home != null && homeSource != null && blend > 0f && blend < 1f) {
            primary.record(size = extent) { scene(source) }
            home.record(size = extent) { scene(homeSource) }
            primary.alpha = 1f - blend; home.alpha = blend
            home.blendMode = androidx.compose.ui.graphics.BlendMode.Plus
        }
        layer.record(size = extent) {
            if (blend >= 1f && homeSource != null) scene(homeSource)
            else if (blend > 0f && primary != null && home != null) { drawLayer(primary); drawLayer(home) }
            else scene(source)
        }
        translate(-padding, -padding) { scale(1f / r, 1f / r, Offset.Zero) { drawLayer(layer) } }
    }
}
