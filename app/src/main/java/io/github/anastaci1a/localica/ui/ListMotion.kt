package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

private data class ScrollTarget(val distance: Float, val measured: Boolean)

/** Re-evaluate the destination while moving, rather than finishing an estimated tween and
 * animating its error backwards. Small seek steps discover variable-height rows before landing.
 * Each scroll phase yields to a new touch. */
private suspend fun ScrollScope.approachItem(duration: Int, viewport: () -> Int, target: () -> ScrollTarget?): Boolean {
    val initial = target() ?: return true
    if (abs(initial.distance) < .5f) return true
    val direction = sign(initial.distance)
    val seconds = duration.coerceAtLeast(1) / 1000f
    val maxSpeed = maxOf(abs(initial.distance) * 2f / seconds, viewport() * 2f)
    var previous = withFrameNanos { it }
    var elapsed = 0f
    while (true) {
        val frame = withFrameNanos { it }
        val previousFrame = previous
        val dt = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f)
        previous = frame
        elapsed += (frame - previousFrame) / 1_000_000_000f
        if (elapsed >= seconds) return false
        val destination = target() ?: return true
        if (abs(destination.distance) < .5f) return true
        // A newly measured target may lie inside the last seek step. Correct before another
        // frame is drawn, rather than scheduling a second, visibly reversing animation.
        if (destination.measured && sign(destination.distance) != direction) {
            scrollBy(destination.distance)
            return true
        }
        val ease = 1f - exp(-dt * 6f / seconds)
        val speedLimit = maxSpeed * dt * (elapsed / .08f).coerceAtMost(1f)
        var budget = minOf(abs(destination.distance) * ease, speedLimit, viewport() * .5f).coerceAtLeast(.5f)
        var consumedFrame = 0f
        while (budget > .1f) {
            val current = target() ?: return true
            if (abs(current.distance) < .5f) return true
            if (current.measured && sign(current.distance) != direction) {
                scrollBy(current.distance)
                return true
            }
            val step = minOf(budget, abs(current.distance), (viewport() / 16f).coerceAtLeast(1f)) * direction
            val consumed = scrollBy(step)
            if (abs(consumed) < .1f) return true // End of list: use the closest available position.
            consumedFrame += abs(consumed)
            budget -= abs(consumed)
            val after = target() ?: return true
            if (after.measured && sign(after.distance) != direction) {
                scrollBy(after.distance)
                return true
            }
        }
        if (consumedFrame < .1f) return true
    }
}

internal suspend fun LazyGridState.easeToTop(reducedMotion: Boolean) = easeToItem(0, reducedMotion)

internal suspend fun LazyListState.easeToItem(index: Int, reducedMotion: Boolean, duration: Int = 350) {
    if (index < 0) return
    if (reducedMotion) { scrollToItem(index); return }
    val visible = layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val distant = abs(index - firstVisibleItemIndex) > visible * 3
    val budget = duration.coerceIn(1, 500)
    if (distant) {
        animateScrollBy(sign((index - firstVisibleItemIndex).toFloat()) * layoutInfo.viewportSize.height * .35f, tween(80))
        scrollToItem((index - sign((index - firstVisibleItemIndex).toFloat()).toInt() * visible * 2).coerceAtLeast(0))
    }
    var arrived = false
    scroll {
        arrived = approachItem((budget - if (distant) 80 else 0).coerceAtLeast(1), { layoutInfo.viewportSize.height }) {
            val items = layoutInfo.visibleItemsInfo
            if (index !in 0 until layoutInfo.totalItemsCount || items.isEmpty()) null else {
                val item = items.firstOrNull { it.index == index }
                val average = items.map { it.size + layoutInfo.mainAxisItemSpacing }.average().toFloat().coerceAtLeast(1f)
                ScrollTarget(item?.offset?.toFloat() ?: ((index - firstVisibleItemIndex) * average - firstVisibleItemScrollOffset), item != null)
            }
        }
    }
    if (!arrived) scrollToItem(index)
}

internal suspend fun LazyGridState.easeToItem(index: Int, reducedMotion: Boolean, topInset: Float = 0f) {
    if (index < 0) return
    if (reducedMotion) { scrollToItem(index, -topInset.toInt()); return }
    val visible = layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val distant = abs(index - firstVisibleItemIndex) > visible * 3
    if (distant) {
        animateScrollBy(sign((index - firstVisibleItemIndex).toFloat()) * layoutInfo.viewportSize.height * .35f, tween(80))
        scrollToItem((index - sign((index - firstVisibleItemIndex).toFloat()).toInt() * visible * 2).coerceAtLeast(0))
    }
    var arrived = false
    scroll {
        arrived = approachItem(if (distant) 420 else 500, { layoutInfo.viewportSize.height }) {
            val items = layoutInfo.visibleItemsInfo
            if (index !in 0 until layoutInfo.totalItemsCount || items.isEmpty()) null else {
                val item = items.firstOrNull { it.index == index }
                val columns = ((items.maxOfOrNull { it.column } ?: 0) + 1).coerceAtLeast(1)
                val average = items.map { it.size.height + layoutInfo.mainAxisItemSpacing }.average().toFloat().coerceAtLeast(1f)
                ScrollTarget((item?.offset?.y?.toFloat() ?: ((index - firstVisibleItemIndex).toFloat() / columns * average - firstVisibleItemScrollOffset)) - topInset, item != null)
            }
        }
    }
    if (!arrived) scrollToItem(index, -topInset.toInt())
}
