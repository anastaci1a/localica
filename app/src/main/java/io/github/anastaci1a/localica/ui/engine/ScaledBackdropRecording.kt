package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Experimental explicit source opt-in. The display list stays stable through renderer changes. */
@Composable
internal fun rememberScaledBackdropRecording(source: GraphicsLayer, factor: Float): ScaledBackdropRecording {
    require(factor > 0f && factor <= 1f)
    val display = rememberGraphicsLayer()
    val recording = remember(source, display, factor) { ScaledBackdropRecording(source, display, factor) }
    SideEffect {
        display.compositingStrategy = CompositingStrategy.Auto
        source.scaleX = 1f / factor; source.scaleY = 1f / factor
        source.pivotOffset = Offset.Zero
    }
    return recording
}

/** One content recording feeds sharp display and a smaller retained sampling raster. */
@Stable
internal class ScaledBackdropRecording(private val source: GraphicsLayer, private val display: GraphicsLayer,
    private val factor: Float) {
    val active get() = factor < 1f

    fun DrawScope.recordSource(extent: IntSize, content: DrawScope.() -> Unit) {
        display.record(this, layoutDirection, extent, content)
        val physical = IntSize(ceil(extent.width * factor).toInt(), ceil(extent.height * factor).toInt())
        source.record(this, layoutDirection, physical) {
            scale(factor, factor, Offset.Zero) { drawLayer(display) }
        }
    }

    fun DrawScope.drawDisplay() { drawLayer(display) }
}
