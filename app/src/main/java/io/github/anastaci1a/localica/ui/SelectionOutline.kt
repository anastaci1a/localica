package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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

@androidx.compose.runtime.Composable
internal fun Modifier.selectionOutline(selected: Boolean, partial: Boolean, color: Color): Modifier {
    return selectionLens(selected, partial)
}

internal fun Modifier.currentSongHighlight(current: Boolean, color: Color) = semantics {
    if (current) stateDescription = "Current song"
}.drawWithCache {
    val random = kotlin.random.Random(47)
    val grain = if (current) List((size.width * size.height / 180f).toInt().coerceAtMost(1600)) {
        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
    } else emptyList()
    onDrawBehind {
        if (current) {
            val inset = 3.dp.toPx()
            val bounds = Size(size.width - inset * 2, size.height - inset * 2)
            val corner = CornerRadius(14.dp.toPx())
            drawRoundRect(color.copy(alpha = .13f), Offset(inset, inset), bounds, corner)
            grain.forEach { drawCircle(Color.White.copy(alpha = .065f), .4.dp.toPx(), it) }
            drawRoundRect(Color.White.copy(alpha = .12f), Offset(inset, inset), bounds, corner, style = Stroke(.7.dp.toPx()))
        }
    }
}
