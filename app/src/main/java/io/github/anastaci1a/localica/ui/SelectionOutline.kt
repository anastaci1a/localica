package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal fun Modifier.selectionOutline(selected: Boolean, partial: Boolean, color: Color) = semantics {
    this.selected = selected
    if (partial) stateDescription = "Partially selected"
}.drawBehind {
    if (selected) {
        val stroke = 2.dp.toPx()
        drawRoundRect(color, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
            CornerRadius(16.dp.toPx()), style = Stroke(stroke,
                pathEffect = if (partial) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())) else null))
    }
}

internal fun Modifier.currentSongHighlight(current: Boolean, color: Color) = semantics {
    if (current) stateDescription = "Current song"
}.drawBehind {
    if (current) {
        val inset = 3.dp.toPx()
        val bounds = Size(size.width - inset * 2, size.height - inset * 2)
        val corner = CornerRadius(14.dp.toPx())
        drawRoundRect(color.copy(alpha = .09f), Offset(inset, inset), bounds, corner)
        drawRoundRect(color.copy(alpha = .65f), Offset(inset, inset), bounds, corner, style = Stroke(1.5.dp.toPx()))
    }
}
