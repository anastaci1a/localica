package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** Project the existing native recording in its source host's coordinates. */
internal fun DrawScope.drawPopupProjection(layer: GraphicsLayer, offset: Offset) =
    translate(offset.x, offset.y) { drawLayer(layer) }
