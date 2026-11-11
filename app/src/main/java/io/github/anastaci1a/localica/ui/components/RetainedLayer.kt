package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import io.github.anastaci1a.localica.ui.engine.drawRetainedLayer

/** Screens place a layer another owner retains through the component layer, never by reaching into the Engine. */
internal fun DrawScope.placeRetainedLayer(layer: GraphicsLayer) = drawRetainedLayer(layer)
