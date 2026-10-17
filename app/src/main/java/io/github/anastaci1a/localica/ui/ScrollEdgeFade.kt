package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Modal bodies opt in once; full-page navigation retains its own edge treatment. */
internal val LocalPopupScrollEdges = staticCompositionLocalOf { false }

private object ScrollEdgeFadeMarker : Modifier.Element
internal fun Modifier.hasScrollEdgeFade() = foldIn(false) { found, element ->
    found || element === ScrollEdgeFadeMarker
}

/** Mask only overflowing scroll content. Fit-to-content bodies need no offscreen buffer. */
internal fun Modifier.scrollEdgeFade(scroll: ScrollState, reverseScrolling: Boolean = false): Modifier {
    if (hasScrollEdgeFade()) return this
    return then(ScrollEdgeFadeMarker).graphicsLayer {
        compositingStrategy = if (scroll.viewportSize > 0 && scroll.maxValue > 0)
            CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithCache {
        val band = 24.dp.toPx().coerceAtMost(size.height / 2)
        val strip = Size(size.width, band)
        val bottomOrigin = Offset(0f, size.height - band)
        val topBrush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = band)
        val bottomBrush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
            startY = bottomOrigin.y, endY = size.height)
        onDrawWithContent {
            drawContent()
            if (band > 0f && scroll.viewportSize > 0 && scroll.maxValue > 0) {
                val before = scroll.value.toFloat()
                val after = (scroll.maxValue - scroll.value).toFloat()
                val top = ((if (reverseScrolling) after else before) / band).coerceIn(0f, 1f)
                val bottom = ((if (reverseScrolling) before else after) / band).coerceIn(0f, 1f)
                if (top > 0f) drawRect(topBrush, size = strip, alpha = top, blendMode = BlendMode.DstOut)
                if (bottom > 0f) drawRect(bottomBrush, topLeft = bottomOrigin, size = strip,
                    alpha = bottom, blendMode = BlendMode.DstOut)
            }
        }
    }
}
