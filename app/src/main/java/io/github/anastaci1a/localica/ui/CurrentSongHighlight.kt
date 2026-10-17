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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal fun Modifier.currentSongHighlight(current: Boolean, color: Color,
    visualSize: (Size) -> Size = { it }) = semantics {
    if (current) stateDescription = "Current song"
}.drawWithCache {
    // Retain the expensive noise texture. The cheap rounded slab is drawn at its
    // visual bounds so pinch never scales its inset, corner radius or outline.
    val image = if (current && size.width > 0 && size.height > 0) {
        ImageBitmap(kotlin.math.ceil(size.width).toInt(), kotlin.math.ceil(size.height).toInt()).also { image ->
        CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(image), size) {
            val random = kotlin.random.Random(47)
            repeat((size.width * size.height / 180f).toInt().coerceAtMost(1600)) {
                drawCircle(Color.White.copy(alpha = .065f), .4.dp.toPx(), Offset(random.nextFloat() * size.width, random.nextFloat() * size.height))
            }
        }
        }
    } else null
    val outline = Path()
    onDrawBehind {
        image?.let {
            // Pinch keeps destination measurement for virtualization, while the
            // visible slab follows both source-to-destination dimensions.
            // Scale only this cached decoration; hit bounds and selection stay put.
            val visual = visualSize(size)
            val inset = 4.dp.toPx()
            val bounds = Size((visual.width - 2 * inset).coerceAtLeast(0f), (visual.height - 2 * inset).coerceAtLeast(0f))
            val origin = Offset(inset + if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - visual.width else 0f, inset)
            val corner = CornerRadius(14.dp.toPx())
            drawRoundRect(color.copy(alpha = .13f), origin, bounds, corner)
            outline.reset()
            outline.addRoundRect(RoundRect(Rect(origin, bounds), corner))
            clipPath(outline) {
                translate(origin.x - inset, 0f) {
                    scale(visual.width / size.width, visual.height / size.height, Offset.Zero) { drawImage(it) }
                }
            }
            drawRoundRect(Color.White.copy(alpha = .12f), origin, bounds, corner, style = Stroke(.7.dp.toPx()))
        }
    }
}
