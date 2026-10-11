package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

private data class ScrollTarget(val distance: Float, val measured: Boolean)

/** Re-evaluate the destination while moving, rather than finishing an estimated tween and
 * animating its error backwards. Small seek steps discover variable-height rows before landing.
 * One scroll mutation also lets a new touch cancel the whole journey. */
private suspend fun ScrollScope.approachItem(duration: Int, viewport: () -> Int, target: () -> ScrollTarget?) {
    val initial = target() ?: return
    if (abs(initial.distance) < .5f) return
    val direction = sign(initial.distance)
    val seconds = duration.coerceAtLeast(1) / 1000f
    val maxSpeed = maxOf(abs(initial.distance) * 2f / seconds, viewport() * 2f)
    var previous = withFrameNanos { it }
    var elapsed = 0f
    while (true) {
        val frame = withFrameNanos { it }
        val dt = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f)
        previous = frame
        elapsed += dt
        val destination = target() ?: return
        if (abs(destination.distance) < .5f) return
        // A newly measured target may lie inside the last seek step. Correct before another
        // frame is drawn, rather than scheduling a second, visibly reversing animation.
        if (destination.measured && sign(destination.distance) != direction) {
            scrollBy(destination.distance)
            return
        }
        val ease = 1f - exp(-dt * 6f / seconds)
        val speedLimit = maxSpeed * dt * (elapsed / .08f).coerceAtMost(1f)
        var budget = minOf(abs(destination.distance) * ease, speedLimit, viewport() * .5f).coerceAtLeast(.5f)
        var consumedFrame = 0f
        while (budget > .1f) {
            val current = target() ?: return
            if (abs(current.distance) < .5f) return
            if (current.measured && sign(current.distance) != direction) {
                scrollBy(current.distance)
                return
            }
            val step = minOf(budget, abs(current.distance), (viewport() / 16f).coerceAtLeast(1f)) * direction
            val consumed = scrollBy(step)
            if (abs(consumed) < .1f) return // End of list: use the closest available position.
            consumedFrame += abs(consumed)
            budget -= abs(consumed)
            val after = target() ?: return
            if (after.measured && sign(after.distance) != direction) {
                scrollBy(after.distance)
                return
            }
        }
        if (consumedFrame < .1f) return
    }
}

internal suspend fun LazyGridState.easeToTop(reducedMotion: Boolean) = easeToItem(0, reducedMotion)

internal suspend fun LazyListState.easeToItem(index: Int, reducedMotion: Boolean, duration: Int = 350) {
    if (index < 0) return
    if (reducedMotion) { scrollToItem(index); return }
    scroll {
        approachItem(duration, { layoutInfo.viewportSize.height }) {
            val items = layoutInfo.visibleItemsInfo
            if (index !in 0 until layoutInfo.totalItemsCount || items.isEmpty()) null else {
                val item = items.firstOrNull { it.index == index }
                val average = items.map { it.size + layoutInfo.mainAxisItemSpacing }.average().toFloat().coerceAtLeast(1f)
                ScrollTarget(item?.offset?.toFloat() ?: ((index - firstVisibleItemIndex) * average - firstVisibleItemScrollOffset), item != null)
            }
        }
    }
}

internal suspend fun LazyGridState.easeToItem(index: Int, reducedMotion: Boolean, topInset: Float = 0f) {
    if (index < 0) return
    if (reducedMotion) { scrollToItem(index, -topInset.toInt()); return }
    scroll {
        approachItem(500, { layoutInfo.viewportSize.height }) {
            val items = layoutInfo.visibleItemsInfo
            if (index !in 0 until layoutInfo.totalItemsCount || items.isEmpty()) null else {
                val item = items.firstOrNull { it.index == index }
                val columns = ((items.maxOfOrNull { it.column } ?: 0) + 1).coerceAtLeast(1)
                val average = items.map { it.size.height + layoutInfo.mainAxisItemSpacing }.average().toFloat().coerceAtLeast(1f)
                ScrollTarget((item?.offset?.y?.toFloat() ?: ((index - firstVisibleItemIndex).toFloat() / columns * average - firstVisibleItemScrollOffset)) - topInset, item != null)
            }
        }
    }
}
