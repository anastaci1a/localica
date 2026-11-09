package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.engine.MaterialAreaReduction as ReductionOwner
import io.github.anastaci1a.localica.ui.engine.rememberMaterialAreaReduction as rememberReductionOwner
import io.github.anastaci1a.localica.ui.engine.materialReductionExtent as reductionExtent

/** Component access to an engine-owned material reduction. No layer lifetime escapes its owner. */
internal class MaterialAreaReduction(private val owner: ReductionOwner) {
    val current: GraphicsLayer? get() = owner.current
    fun DrawScope.record(extent: IntSize, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer =
        with(owner) { record(extent, minimum, draw) }
    fun DrawScope.recordFullBounds(bounds: Size, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer =
        with(owner) { recordFullBounds(bounds, minimum, draw) }
    fun DrawScope.drawCurrent(extent: Size) = with(owner) { drawCurrent(extent) }
    companion object {
        val legacyHalvings: Boolean get() = ReductionOwner.legacyHalvings
    }
}

@Composable internal fun rememberMaterialAreaReduction(): MaterialAreaReduction {
    val owner = rememberReductionOwner()
    return remember(owner) { MaterialAreaReduction(owner) }
}

internal fun materialReductionExtent(size: IntSize): IntSize = reductionExtent(size)
