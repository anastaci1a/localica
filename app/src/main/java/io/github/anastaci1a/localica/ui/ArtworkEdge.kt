package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*

/** Keep the center sharp while exposing the prepared soft cover around its perimeter. */
internal fun Modifier.artworkEdgeFeather(amount: Float, band: Float, coverBlend: Float = 0f): Modifier =
    if (amount <= 0f && coverBlend <= 0f) this else
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val stops = Array(33) { index ->
            val position = index / 32f
            val t = (minOf(position, 1f - position) / band).coerceIn(0f, 1f)
            position to Color.Black.copy(alpha = 1f - amount.coerceIn(0f, 1f) * (1f - t * t * (3f - 2f * t)))
        }
        val horizontal = Brush.horizontalGradient(*stops)
        val vertical = Brush.verticalGradient(*stops)
        // The full-cover continuation and edge feather multiply the same alpha.
        // Keep both in one offscreen layer instead of rendering a second cover texture.
        val continuation = if (coverBlend > 0f) Brush.verticalGradient(*Array(33) { index ->
            val y = index / 32f
            val edge = if (y < .14f) (.14f - y) / .14f else (y - .68f) / .32f
            val t = edge.coerceIn(0f, 1f)
            y to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t) * coverBlend)
        }) else null
        onDrawWithContent {
            drawContent()
            drawRect(horizontal, blendMode = BlendMode.DstIn)
            drawRect(vertical, blendMode = BlendMode.DstIn)
            continuation?.let { drawRect(it, blendMode = BlendMode.DstIn) }
        }
    }
