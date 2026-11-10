package io.github.anastaci1a.localica.ui.engine

import android.os.Build
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
 * every step; it must be divided out only after the final premultiplied sum. */
internal class MaterialAreaReduction(private val graphics: GraphicsContext) {
    private val levels = mutableListOf<GraphicsLayer>()
    /** The level the latest [record] returned, with whatever effect its reader installed; null before any. */
    var current: GraphicsLayer? = null
        private set
    private fun level(index: Int): GraphicsLayer {
        while (levels.size <= index) levels += graphics.createGraphicsLayer().apply {
        GlassWork.event("layer")
            compositingStrategy = CompositingStrategy.Offscreen
            clip = true
        }
        return levels[index]
    }
    /** Reduce the power-of-two extent until both dimensions are at most [minimum] (a power of two).
     * On Android 13+ every step is one exact block mean of up to 8×8 texels ([MaterialBlockMean]): a
     * 512 px side takes three passes instead of nine, with every texel read once. Older Android keeps
     * bilinear 2:1 halvings, which are exact boxes at that ratio, for the shared 32×32 thumbnail. */
    fun DrawScope.record(extent: IntSize, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer {
        var dimensions = materialReductionExtent(extent)
        var previous = level(0)
        previous.renderEffect = null
        previous.record(Density(density, fontScale), layoutDirection, dimensions, draw)
        var index = 1
        val blockMeans = Build.VERSION.SDK_INT >= 33 && !legacyHalvings
        while (dimensions.width > minimum || dimensions.height > minimum) {
            val input = previous
            val before = dimensions
            val next: IntSize
            previous = level(index++)
            previous.renderEffect = null
            if (blockMeans) {
                fun block(side: Int) = minOf(8, maxOf(1, side / minimum))
                val block = IntSize(block(before.width), block(before.height))
                next = IntSize(before.width / block.width, before.height / block.height)
                input.renderEffect = MaterialBlockMean.effect(block)
                // Drawn unscaled: the destination clip keeps the first texel of every block, whose
                // value is the block's mean. No draw samples a texture below half scale.
                previous.record(size = next) { drawLayer(input) }
            } else {
                next = IntSize((before.width / 2).coerceAtLeast(minimum).coerceAtMost(before.width),
                    (before.height / 2).coerceAtLeast(minimum).coerceAtMost(before.height))
                previous.record(size = next) {
                    scale(next.width.toFloat() / before.width, next.height.toFloat() / before.height, Offset.Zero) {
                        drawLayer(input)
                    }
                }
            }
            dimensions = next
        }
        current = previous
        return previous
    }
    /** Map the exact footprint onto the whole reduction texture. Transparent power-of-two
     * padding would otherwise amplify RGBA8 rounding when the mean is unpremultiplied,
     * giving identical backdrops different colours for different material dimensions. */
    fun DrawScope.recordFullBounds(bounds: Size, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer {
        val extent = materialReductionExtent(IntSize(ceil(bounds.width).toInt(), ceil(bounds.height).toInt()))
        return record(extent, minimum) {
            scale(extent.width / bounds.width, extent.height / bounds.height, Offset.Zero) {
                clipRect(right = bounds.width, bottom = bounds.height, block = draw)
            }
        }
    }
    /** Draw the retained styled mean without transferring layer ownership to a component. */
    fun DrawScope.drawCurrent(extent: Size) {
        val styled = current ?: return
        scale(extent.width / styled.size.width.coerceAtLeast(1), extent.height / styled.size.height.coerceAtLeast(1),
            Offset.Zero) { drawLayer(styled) }
    }
    fun release() { levels.forEach(graphics::releaseGraphicsLayer); levels.clear(); current = null }

    companion object {
        /** Diagnostics only: in DEBUG builds a test may restore the whole pre-#691 None path (bilinear 2:1
         * chain to 1×1, one-tap style into a 1×1 copy) to measure final-output drift against the block-mean
         * path on one device. Only androidTest sets it. Release builds constant-fold [legacyHalvings] to
         * false; R8 removes the switch and its accessors. Compose cache reads may retain unreachable legacy helper code. */
        @androidx.annotation.VisibleForTesting
        @Volatile var legacyOverride = false
        val legacyHalvings: Boolean get() = io.github.anastaci1a.localica.BuildConfig.DEBUG && legacyOverride
    }
}

@Composable internal fun rememberMaterialAreaReduction(): MaterialAreaReduction {
    val graphics = LocalGraphicsContext.current
    val reduction = remember(graphics) { MaterialAreaReduction(graphics) }
    DisposableEffect(reduction) { onDispose { reduction.release() } }
    return reduction
}
