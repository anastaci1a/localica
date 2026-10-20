package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** Keep glyphs at their full-size font metrics; animate the layer, not the font rasterization.
 * Measure enough unscaled width to preserve the same visible clipping/marquee boundary. */
internal fun Modifier.scalePlayerText(scale: Float): Modifier = layout { measurable, constraints ->
    val width = (constraints.maxWidth / scale).roundToInt()
    val text = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0))
    layout(constraints.maxWidth, constraints.constrainHeight((text.height * scale).roundToInt())) {
        text.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
