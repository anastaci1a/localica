package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** The live row and virtualized pinch copy share their frost without scaling its contour. */
internal class CurrentSongHighlightRenderer {
    private var texture: ImageBitmap? = null
    private var textureSize = Size.Zero
    private var textureDensity = 0f
    private val outline = Path()

    fun DrawScope.draw(bounds: Rect, color: Color, sourceSize: Size = bounds.size, alpha: Float = 1f) {
        if (sourceSize.width <= 0f || sourceSize.height <= 0f || bounds.isEmpty || alpha <= 0f) return
        if (texture == null || textureSize != sourceSize || textureDensity != density) {
            textureSize = sourceSize
            textureDensity = density
            texture = ImageBitmap(kotlin.math.ceil(sourceSize.width).toInt(), kotlin.math.ceil(sourceSize.height).toInt()).also { image ->
                CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(image), sourceSize) {
                    val random = kotlin.random.Random(47)
                    repeat((sourceSize.width * sourceSize.height / 180f).toInt().coerceAtMost(1600)) {
                        drawCircle(Color.White.copy(alpha = .065f), .4.dp.toPx(),
                            Offset(random.nextFloat() * sourceSize.width, random.nextFloat() * sourceSize.height))
                    }
                }
            }
        }
        val contour = mediaItemContour(bounds, MediaSelectionGap.toPx())
        val corner = contour.topLeftCornerRadius
        drawRoundRect(color.copy(alpha = .13f * alpha), bounds.topLeft, bounds.size, corner)
        outline.reset()
        outline.addRoundRect(contour)
        clipPath(outline) {
            translate(bounds.left, bounds.top) {
                scale(bounds.width / sourceSize.width, bounds.height / sourceSize.height, Offset.Zero) {
                    drawImage(requireNotNull(texture), alpha = alpha)
                }
            }
            // Keep the faint centered stroke inside the shared silhouette.
            drawRoundRect(Color.White.copy(alpha = .12f * alpha), bounds.topLeft, bounds.size, corner,
                style = Stroke(.7.dp.toPx()))
        }
    }
}

internal class RetainedCurrentSongHighlight(val color: Color) {
    val renderer = CurrentSongHighlightRenderer()
}

internal fun Modifier.currentSongHighlight(current: Boolean, color: Color,
    visualSize: (Size) -> Size = { it }) = semantics {
    if (current) stateDescription = "Current song"
}.drawWithCache {
    val renderer = CurrentSongHighlightRenderer()
    onDrawBehind {
        if (current) with(renderer) {
            draw(mediaItemBounds(size, visualSize(size), layoutDirection), color, sourceSize = size)
        }
    }
}
