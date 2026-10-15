package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal fun Modifier.currentSongHighlight(current: Boolean, color: Color,
    visualHeight: (Float) -> Float = { it }) = semantics {
    if (current) stateDescription = "Current song"
}.drawWithCache {
    // The highlight is immutable between size/color/current-item changes. Retain
    // its exact-resolution texture instead of replaying up to 1,600 circles on
    // every scroll and again for every glass surface sampling this row.
    val image = if (current && size.width > 0 && size.height > 0) {
        ImageBitmap(kotlin.math.ceil(size.width).toInt(), kotlin.math.ceil(size.height).toInt()).also { image ->
        CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(image), size) {
            val inset = 4.dp.toPx()
            val bounds = Size(size.width - inset * 2, size.height - inset * 2)
            val corner = CornerRadius(14.dp.toPx())
            drawRoundRect(color.copy(alpha = .13f), Offset(inset, inset), bounds, corner)
            val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset(inset, inset), bounds), corner)) }
            val random = kotlin.random.Random(47)
            clipPath(outline) { repeat((size.width * size.height / 180f).toInt().coerceAtMost(1600)) {
                drawCircle(Color.White.copy(alpha = .065f), .4.dp.toPx(), Offset(random.nextFloat() * size.width, random.nextFloat() * size.height))
            } }
            drawRoundRect(Color.White.copy(alpha = .12f), Offset(inset, inset), bounds, corner, style = Stroke(.7.dp.toPx()))
        }
        }
    } else null
    onDrawBehind {
        image?.let {
            // Pinch keeps destination measurement for virtualization, while the
            // visible slab follows the content's source-to-destination height.
            // Scale only this cached decoration; hit bounds and selection stay put.
            scale(1f, visualHeight(size.height) / size.height, Offset.Zero) {
                drawImage(it)
            }
        }
    }
}
