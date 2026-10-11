package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/** Animate consumed pixels, so distant destinations do not use LazyLayout's item teleport shortcut. */
internal suspend fun LazyGridState.easeToTop(reducedMotion: Boolean) {
    if (reducedMotion) { scrollToItem(0); return }
    while (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0) {
        val before = firstVisibleItemIndex to firstVisibleItemScrollOffset
        val items = layoutInfo.visibleItemsInfo
        val row = items.firstOrNull()?.row?.coerceAtLeast(0) ?: firstVisibleItemIndex
        val height = items.map { it.size.height }.average().takeIf { it.isFinite() && it > 0 } ?: 64.0
        val distance = (row * height + firstVisibleItemScrollOffset).toFloat().coerceAtLeast(height.toFloat())
        animateScrollBy(-distance, tween(500))
        if (before == (firstVisibleItemIndex to firstVisibleItemScrollOffset)) break
    }
}
internal suspend fun LazyListState.easeToItem(index: Int, reducedMotion: Boolean, duration: Int = 350) {
    if (reducedMotion) { scrollToItem(index); return }
    repeat(3) {
        val items = layoutInfo.visibleItemsInfo
        val target = items.firstOrNull { it.index == index }
        val average = items.map { it.size }.average().takeIf { it.isFinite() && it > 0 } ?: 64.0
        val distance = target?.offset?.toFloat() ?: ((index - firstVisibleItemIndex) * average - firstVisibleItemScrollOffset).toFloat()
        if (kotlin.math.abs(distance) < 1f) return
        val consumed = animateScrollBy(distance, tween(duration))
        if (kotlin.math.abs(consumed) < 1f) return
    }
}

internal suspend fun LazyGridState.easeToItem(index: Int, reducedMotion: Boolean, topInset: Float = 0f) {
    if (reducedMotion) { scrollToItem(index, -topInset.toInt()); return }
    repeat(5) {
        val items = layoutInfo.visibleItemsInfo
        val target = items.firstOrNull { it.index == index }
        val columns = (items.maxOfOrNull { it.column } ?: 0) + 1
        val height = items.map { it.size.height }.average().takeIf { it.isFinite() && it > 0 } ?: 64.0
        val distance = (target?.offset?.y?.toFloat() ?: (((index - firstVisibleItemIndex) / columns) * height - firstVisibleItemScrollOffset).toFloat()) - topInset
        if (kotlin.math.abs(distance) < 1) return
        if (kotlin.math.abs(animateScrollBy(distance, tween(500))) < 1) return
    }
}
