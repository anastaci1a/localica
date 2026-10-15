package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight

/** Measure only stateless faces; never place or expose their semantics/input. */
@Composable
internal fun PopupMeasurementMaximum(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.clearAndSetSemantics { },
        measurePolicy = object : androidx.compose.ui.layout.MeasurePolicy {
            override fun androidx.compose.ui.layout.MeasureScope.measure(children: List<androidx.compose.ui.layout.Measurable>, constraints: androidx.compose.ui.unit.Constraints): androidx.compose.ui.layout.MeasureResult {
                val measured = children.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                return layout(constraints.constrainWidth(measured.maxOfOrNull { it.width } ?: 0),
                    constraints.constrainHeight(measured.maxOfOrNull { it.height } ?: 0)) { }
            }

        })
}

/** One bounded viewport, sized for all potential states, with each live page composed once.
 * No remembered high-water mark: changing the declared possibilities (including removing an
 * action), typography or window constraints immediately invalidates the envelope. Ordinary
 * draft changes never alter the declaration. Measure natural content before applying the cap.
 */
@Composable
internal fun StablePopupBody(modifier: Modifier = Modifier, measurement: @Composable () -> Unit,
    content: @Composable () -> Unit) {
    Layout(modifier = modifier.fillMaxWidth(), content = {
        Box(Modifier.clearAndSetSemantics { }) { measurement() }
        Box { content() }
    }) { children, constraints ->
        require(constraints.hasBoundedWidth)
        val envelope = children[0].measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val height = envelope.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val current = children[1].measure(constraints.copy(minHeight = 0, maxHeight = height))
        layout(constraints.maxWidth, height) { current.place(0, 0) }
    }
}
