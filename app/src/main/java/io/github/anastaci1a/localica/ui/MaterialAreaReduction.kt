package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

internal fun materialReductionExtent(size: IntSize): IntSize {
    fun power(value: Int): Int {
        var result = 1
        while (result < value) result *= 2
        return result
    }
    return IntSize(power(size.width), power(size.height))
}

/** Retained box-reduction levels. Transparent padding carries odd edge coverage through
 * every 2x step; it must be divided out only after the final premultiplied sum. */
internal class MaterialAreaReduction(private val graphics: GraphicsContext) {
    private val levels = mutableListOf<GraphicsLayer>()
    private fun level(index: Int): GraphicsLayer {
        while (levels.size <= index) levels += graphics.createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
            clip = true
        }
        return levels[index]
    }
    fun DrawScope.record(extent: IntSize, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer {
        var dimensions = materialReductionExtent(extent)
        var previous = level(0)
        previous.record(Density(density, fontScale), layoutDirection, dimensions, draw)
        var index = 1
        while (dimensions.width > minimum || dimensions.height > minimum) {
            val next = IntSize((dimensions.width / 2).coerceAtLeast(minimum).coerceAtMost(dimensions.width),
                (dimensions.height / 2).coerceAtLeast(minimum).coerceAtMost(dimensions.height))
            val input = previous
            val before = dimensions
            previous = level(index++)
            previous.record(size = next) {
                scale(next.width.toFloat() / before.width, next.height.toFloat() / before.height, Offset.Zero) {
                    drawLayer(input)
                }
            }
            dimensions = next
        }
        return previous
    }
    /** Map the exact footprint onto the whole reduction texture. Transparent power-of-two
     * padding would otherwise amplify RGBA8 rounding when the mean is unpremultiplied,
     * giving identical backdrops different colours for different material dimensions. */
    fun DrawScope.recordFullBounds(bounds: Size, sampleSide: Int? = null, draw: DrawScope.() -> Unit): GraphicsLayer {
        val extent = if (sampleSide != null) {
            require(sampleSide == 8 || sampleSide == 16)
            IntSize(sampleSide, sampleSide)
        } else materialReductionExtent(IntSize(ceil(bounds.width).toInt(), ceil(bounds.height).toInt()))
        return record(extent) {
            scale(extent.width / bounds.width, extent.height / bounds.height, Offset.Zero) {
                clipRect(right = bounds.width, bottom = bounds.height, block = draw)
            }
        }
    }
    fun release() { levels.forEach(graphics::releaseGraphicsLayer); levels.clear() }
}

@Composable internal fun rememberMaterialAreaReduction(): MaterialAreaReduction {
    val graphics = LocalGraphicsContext.current
    val reduction = remember(graphics) { MaterialAreaReduction(graphics) }
    DisposableEffect(reduction) { onDispose { reduction.release() } }
    return reduction
}
