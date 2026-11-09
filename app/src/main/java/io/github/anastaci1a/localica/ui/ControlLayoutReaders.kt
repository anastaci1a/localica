package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrain

/** The same constrained dimensions as size(value), read during measurement. */
internal fun Modifier.liveControlSize(size: () -> Dp): Modifier = layout { measurable, constraints ->
    val pixels = size().roundToPx().coerceAtLeast(0)
    val child = measurable.measure(constraints.constrain(Constraints.fixed(pixels, pixels)))
    layout(child.width, child.height) { child.placeRelative(0, 0) }
}

/** The same constrained width as width(value), read during measurement. */
internal fun Modifier.liveControlWidth(width: () -> Dp): Modifier = layout { measurable, constraints ->
    val pixels = width().roundToPx().coerceAtLeast(0)
    val child = measurable.measure(constraints.constrain(Constraints(minWidth = pixels, maxWidth = pixels)))
    layout(child.width, child.height) { child.placeRelative(0, 0) }
}

/** Offset without an extra layer, retaining the value overload's RTL placement. */
internal fun Modifier.liveControlOffset(x: () -> Dp, y: () -> Dp): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    layout(child.width, child.height) { child.placeRelative(x().roundToPx(), y().roundToPx()) }
}
