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

/** Read scale with the same full-size font and width rounding during measurement. */
internal fun Modifier.scalePlayerText(scale: () -> Float): Modifier = layout { measurable, constraints ->
    val value = scale()
    val width = (constraints.maxWidth / value).roundToInt()
    val text = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0))
    layout(constraints.maxWidth, constraints.constrainHeight((text.height * value).roundToInt())) {
        text.placeWithLayer(0, 0) {
            scaleX = value; scaleY = value; transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** Match offset(x,y).width(width), including RTL placement and independent rounding. */
internal fun Modifier.playerTextBounds(x: () -> androidx.compose.ui.unit.Dp, y: () -> androidx.compose.ui.unit.Dp,
    width: () -> androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val w = width().roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val child = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(child.width, child.height) { child.placeRelative(x().roundToPx(), y().roundToPx()) }
}
