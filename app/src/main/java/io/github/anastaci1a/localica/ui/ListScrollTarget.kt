package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlin.math.roundToInt
import kotlin.math.sign

/** A lazy destination shared by visible affordances and the motion they admit. */
internal data class ListScrollTarget(val index: Int, val offset: Int = 0) {
    fun displacement(state: LazyGridState): Float {
        val layout = state.layoutInfo
        if (index !in 0 until layout.totalItemsCount) return 0f
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val first = layout.visibleItemsInfo.firstOrNull { it.index == 0 }
        val itemTop = item?.offset?.y ?: first?.takeIf { index == 1 }?.let { it.offset.y + it.size.height }
        val distance = itemTop?.let { it - layout.beforeContentPadding + offset }?.toFloat()
            ?: if (index < state.firstVisibleItemIndex) -Float.MAX_VALUE else Float.MAX_VALUE
        val last = layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 }
        val minimum = if (!state.canScrollBackward) 0f else first?.let {
            (it.offset.y - layout.beforeContentPadding).toFloat()
        } ?: -Float.MAX_VALUE
        val maximum = if (!state.canScrollForward) 0f else last?.let {
            (it.offset.y + it.size.height + layout.afterContentPadding - layout.viewportEndOffset).coerceAtLeast(0).toFloat()
        } ?: Float.MAX_VALUE
        return distance.coerceIn(minimum.coerceAtMost(0f), maximum)
    }

    fun direction(state: LazyGridState): Int = sign(displacement(state)).toInt()

    companion object {
        /** Anchor to the first ordinary row so distance estimation includes the measured
         * full-span header even while that unusually tall item is recycled. */
        fun header(headerHeight: Int, scroll: Float = 0f) =
            ListScrollTarget(1, scroll.roundToInt() - headerHeight)

        fun below(index: Int, inset: Float) = ListScrollTarget(index, -inset.roundToInt())
    }
}

internal suspend fun LazyGridState.easeToTarget(target: ListScrollTarget, reducedMotion: Boolean) =
    easeToItem(target.index, reducedMotion, -target.offset.toFloat())
