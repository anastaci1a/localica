package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
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
internal fun Modifier.scrollEdgeFade(scroll: ScrollState, reverseScrolling: Boolean = false): Modifier =
    fadingScrollEdges({ scroll.viewportSize > 0 && scroll.maxValue > 0 },
        { (if (reverseScrolling) scroll.maxValue - scroll.value else scroll.value).toFloat() },
        { (if (reverseScrolling) scroll.value else scroll.maxValue - scroll.value).toFloat() })

/** Lazy bodies use measured item bounds, including partial rows and content padding. */
internal fun Modifier.scrollEdgeFade(scroll: LazyListState): Modifier =
    fadingScrollEdges({ scroll.canScrollBackward || scroll.canScrollForward }, {
        val layout = scroll.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        when {
            !scroll.canScrollBackward || first == null -> 0f
            first.index > 0 -> Float.MAX_VALUE
            else -> (layout.viewportStartOffset - first.offset).toFloat()
        }
    }, {
        val layout = scroll.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        when {
            !scroll.canScrollForward || last == null -> 0f
            last.index < layout.totalItemsCount - 1 -> Float.MAX_VALUE
            else -> (last.offset + last.size - layout.viewportEndOffset).toFloat()
        }
    })

internal fun Modifier.scrollEdgeFade(scroll: LazyGridState): Modifier =
    fadingScrollEdges({ scroll.canScrollBackward || scroll.canScrollForward }, {
        val layout = scroll.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        when {
            !scroll.canScrollBackward || first == null -> 0f
            first.index > 0 -> Float.MAX_VALUE
            else -> (layout.viewportStartOffset - first.offset.y).toFloat()
        }
    }, {
        val layout = scroll.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        when {
            !scroll.canScrollForward || last == null -> 0f
            last.index < layout.totalItemsCount - 1 -> Float.MAX_VALUE
            // A row's final item may be shorter than its neighbors.
            else -> ((layout.visibleItemsInfo.maxOfOrNull { it.offset.y + it.size.height } ?: 0) - layout.viewportEndOffset).toFloat()
        }
    })

private fun Modifier.fadingScrollEdges(overflow: () -> Boolean, hiddenAbove: () -> Float,
    hiddenBelow: () -> Float): Modifier {
    if (hasScrollEdgeFade()) return this
    return then(ScrollEdgeFadeMarker).graphicsLayer {
        compositingStrategy = if (overflow())
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
            if (band > 0f && overflow()) {
                val top = (hiddenAbove() / band).coerceIn(0f, 1f)
                val bottom = (hiddenBelow() / band).coerceIn(0f, 1f)
                if (top > 0f) drawRect(topBrush, size = strip, alpha = top, blendMode = BlendMode.DstOut)
                if (bottom > 0f) drawRect(bottomBrush, topLeft = bottomOrigin, size = strip,
                    alpha = bottom, blendMode = BlendMode.DstOut)
            }
        }
    }
}
