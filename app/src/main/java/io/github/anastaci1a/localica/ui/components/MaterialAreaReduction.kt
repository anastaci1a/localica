package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.engine.MaterialAreaReduction as ReductionOwner
import io.github.anastaci1a.localica.ui.engine.rememberMaterialAreaReduction as rememberReductionOwner
import io.github.anastaci1a.localica.ui.engine.materialReductionExtent as reductionExtent

/** Component access to an engine-owned material reduction. No layer lifetime escapes its owner. */
internal class MaterialAreaReduction(private val owner: ReductionOwner) {
    fun DrawScope.record(extent: IntSize, minimum: Int = 1, draw: DrawScope.() -> Unit): GraphicsLayer =
        with(owner) { record(extent, minimum, draw) }
}

@Composable internal fun rememberMaterialAreaReduction(): MaterialAreaReduction {
    val owner = rememberReductionOwner()
    return remember(owner) { MaterialAreaReduction(owner) }
}

internal fun materialReductionExtent(size: IntSize): IntSize = reductionExtent(size)
