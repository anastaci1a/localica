package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/** Frame of the retained floating return control. It measures its one face without the
 * caller's minimum, takes the face's size within the caller's constraints and places the
 * face at its start edge, so the caller's offset and presentation layer act on the face's
 * measured bounds. Lifetime, motion and input stay with the caller. */
@Composable
internal fun ReturnControlSlot(modifier: Modifier, face: @Composable () -> Unit) {
    Layout(face, modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.placeRelative(0, 0)
        }
    }
}

/** Measures the face's height without placing a drawing or an input target, so the list can
 * reserve its end clearance before scrolling reveals the control. */
@Composable
internal fun ReturnControlHeightProbe(onHeightMeasured: (Int) -> Unit, face: @Composable () -> Unit) {
    Layout(content = {
        Box(Modifier.onSizeChanged { onHeightMeasured(it.height) }) { face() }
    }) { measurables, constraints ->
        measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(0, 0) {}
    }
}
