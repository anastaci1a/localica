package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyLayoutScrollScope as listScrollScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyLayoutScrollScope as gridScrollScope
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign

/** One time curve and one scroll owner. A distant target is reached by one cold jump to about a
 * viewport and a half before it, then the final stretch glides over the whole duration with scrollBy, so
 * prefetch and reuse apply and the journey composes about two fresh viewports in total, instead
 * of one per frame. Finish a newly discovered target before drawing any overshoot. */
private suspend fun LazyLayoutScrollScope.approachItem(index: Int, offset: Int, duration: Int,
    viewport: () -> Int, columns: () -> Int) {
    if (index !in 0 until itemCount) return
    val distance = calculateDistanceTo(index, offset).toFloat()
    var direction = sign(distance)
    if (direction == 0f) return
    val lead = (viewport() * 1.5f).toInt().coerceAtLeast(1)
    if (abs(distance) > lead * 1.5f && index !in firstVisibleItemIndex..lastVisibleItemIndex) {
        val slots = columns().coerceAtLeast(1)
        val rows = (index - firstVisibleItemIndex).toFloat() / slots
        val stride = if (rows == 0f) 0f else abs((distance + firstVisibleItemScrollOffset - offset) / rows)
        if (stride > .5f) {
            val back = ceil(lead / stride).toInt().coerceAtLeast(1)
            val dir = direction.toInt()
            val near = (index - dir * back * slots).coerceIn(0, itemCount - 1)
            // Jump only when it stays on the near side of the target and actually advances.
            if ((near - index) * dir < 0 && (near - firstVisibleItemIndex) * dir > 0) {
                snapToItem(near, offset)
                direction = sign(calculateDistanceTo(index, offset).toFloat())
                if (direction == 0f) return
            }
        }
    }
    val start = withFrameNanos { it }
    var previous = 0f
    while (true) {
        val frame = withFrameNanos { it }
        val time = ((frame - start) / (duration.coerceIn(1, 500) * 1_000_000f)).coerceIn(0f, 1f)
        val progress = FastOutSlowInEasing.transform(time)
        val remaining = calculateDistanceTo(index, offset).toFloat()
        if (abs(remaining) < .5f) return
        if (time >= 1f) { snapToItem(index, offset); return }
        val fraction = ((progress - previous) / (1f - previous)).coerceIn(0f, 1f)
        previous = progress
        val step = remaining * fraction
        if (abs(scrollBy(step)) < .1f && abs(step) > .5f) return
        if (index in firstVisibleItemIndex..lastVisibleItemIndex) {
            val measured = calculateDistanceTo(index, offset).toFloat()
            if (abs(measured) < .5f) return
            if (sign(measured) != direction) { scrollBy(measured); return }
        }
    }
}

internal suspend fun LazyGridState.easeToTop(reducedMotion: Boolean) = easeToItem(0, reducedMotion)

internal suspend fun LazyListState.easeToItem(index: Int, reducedMotion: Boolean, duration: Int = 350) {
    if (index < 0) return
    // scroll waits for the first layout. Before that, a populated list reports
    // zero items and would silently discard an initial follow-current request.
    scroll {
        val scope = listScrollScope(this@easeToItem, this)
        if (index < scope.itemCount) {
            if (reducedMotion) scope.snapToItem(index, 0)
            else scope.approachItem(index, 0, duration, { layoutInfo.viewportSize.height }, { 1 })
        }
    }
}

internal suspend fun LazyGridState.easeToItem(index: Int, reducedMotion: Boolean, topInset: Float = 0f) {
    if (index < 0) return
    scroll {
        val scope = gridScrollScope(this@easeToItem, this)
        if (index < scope.itemCount) {
            if (reducedMotion) scope.snapToItem(index, -topInset.roundToInt())
            else scope.approachItem(index, -topInset.roundToInt(), 500,
                { layoutInfo.viewportSize.height }, { (layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1 })
        }
    }
}
