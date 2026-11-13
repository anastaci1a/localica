package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** One laid-out item of a vertical lazy list or grid, in pixels relative to the content start. */
internal class ScrollbarItem(val index: Int, val top: Int, val height: Int)

/** Everything the scrollbar needs from one layout pass. [windowEnd] is the content-area end in item coordinates. */
internal class ScrollbarFrame(
    val items: List<ScrollbarItem>, val totalItems: Int, val firstScrollOffset: Int,
    val windowEnd: Int, val viewport: Int, val atEnd: Boolean,
)

internal fun LazyGridState.scrollbarFrame(): ScrollbarFrame {
    val info = layoutInfo
    return ScrollbarFrame(
        info.visibleItemsInfo.map { ScrollbarItem(it.index, it.offset.y, it.size.height) },
        info.totalItemsCount, firstVisibleItemScrollOffset,
        info.viewportEndOffset - info.afterContentPadding,
        (info.viewportEndOffset - info.afterContentPadding - info.viewportStartOffset - info.beforeContentPadding).coerceAtLeast(1),
        !canScrollForward,
    )
}

internal fun LazyListState.scrollbarFrame(): ScrollbarFrame {
    val info = layoutInfo
    return ScrollbarFrame(
        info.visibleItemsInfo.map { ScrollbarItem(it.index, it.offset, it.size) },
        info.totalItemsCount, firstVisibleItemScrollOffset,
        info.viewportEndOffset - info.afterContentPadding,
        (info.viewportEndOffset - info.afterContentPadding - info.viewportStartOffset - info.beforeContentPadding).coerceAtLeast(1),
        !canScrollForward,
    )
}

/**
 * The one position model behind every scrollbar. Positions are pixel distances: measured item extents
 * are cached by index (a grid line's pitch shared among its items), unmeasured items count as the running
 * average. Position = extent before the viewport + intra-line offset; remaining = extent below the viewport.
 * The thumb fraction is position / (position + remaining), so the unknown remainder shrinks to exactly zero
 * as the end comes into view and there is no clamp jump. Thumb size and drag targets use the same numbers.
 */
internal class ScrollbarModel {
    private var extent = FloatArray(0)
    private var lineFirst = IntArray(0)
    private var measuredSum = 0.0
    private var measuredCount = 0
    private var spacing = 0f
    var position = 0f; private set
    var remaining = 0f; private set
    var viewport = 1f; private set
    private var seen = false
    private var shownRemaining = 0f

    /** Scrollable distance in the model's units. */
    val scrollable get() = position + remaining
    val fraction get() = if (scrollable <= 0f) 0f else (position / scrollable).coerceIn(0f, 1f)
    private val average get() = if (measuredCount == 0) 0f else (measuredSum / measuredCount).toFloat()

    fun observe(frame: ScrollbarFrame): Float {
        if (frame.totalItems != extent.size) reset(frame.totalItems)
        val items = frame.items
        if (items.isEmpty() || frame.totalItems == 0) { position = 0f; remaining = 0f; return 0f }
        viewport = frame.viewport.toFloat()
        var from = 0
        var firstLine = items[0].index
        var lastLineBottom = 0
        while (from < items.size) {
            var to = from + 1
            val top = items[from].top
            var tallest = items[from].height
            while (to < items.size && items[to].top == top) { tallest = maxOf(tallest, items[to].height); to++ }
            val pitch = if (to < items.size) {
                (items[to].top - top).toFloat().also { if (it >= tallest) spacing = it - tallest }
            } else tallest + spacing
            val share = pitch / (to - from)
            for (k in from until to) record(items[k].index, share, items[from].index)
            lastLineBottom = top + tallest
            from = to
        }
        val lastIndex = items.last().index
        val average = average
        var before = 0f
        for (i in 0 until firstLine) before += if (extent[i].isNaN()) average else extent[i]
        position = before + frame.firstScrollOffset
        val raw = if (frame.atEnd) 0f else {
            var after = (lastLineBottom - frame.windowEnd).coerceAtLeast(0).toFloat()
            for (i in lastIndex + 1 until frame.totalItems) after += if (extent[i].isNaN()) average else extent[i]
            after
        }
        remaining = smoothed(raw, position - lastPosition)
        lastPosition = position
        return fraction
    }

    /** Thumb length for a track: the viewport's share of the whole content, kept between [min] and [max]. */
    fun thumbSize(track: Float, min: Float, max: Float): Float {
        val whole = scrollable + viewport
        return (track * viewport / whole).coerceIn(min, maxOf(min, max))
    }

    /** The inverse of [fraction]: the item (its grid line's first item) and pixel offset that put the thumb at [f]. */
    fun target(f: Float): Pair<Int, Int> {
        if (extent.isEmpty()) return 0 to 0
        if (f >= 1f) return lastItemTarget()
        return targetAt(f.coerceIn(0f, 1f) * scrollable)
    }

    /** The item and pixel offset [delta] pixels from the observed position. */
    fun targetBy(delta: Float): Pair<Int, Int> =
        if (extent.isEmpty()) 0 to 0 else targetAt((position + delta).coerceAtLeast(0f))

    private fun targetAt(wanted: Float): Pair<Int, Int> {
        val average = average
        var cumulative = 0f
        var i = 0
        while (i < extent.size) {
            val h = if (extent[i].isNaN()) average else extent[i]
            if (cumulative + h > wanted) break
            cumulative += h
            i++
        }
        if (i >= extent.size) return lastItemTarget()
        if (extent[i].isNaN() || lineFirst[i] < 0) return i to 0
        val first = lineFirst[i]
        var start = 0f
        for (k in 0 until first) start += if (extent[k].isNaN()) average else extent[k]
        return first to (wanted - start).toInt().coerceAtLeast(0)
    }

    private fun lastItemTarget(): Pair<Int, Int> = (extent.size - 1) to 0

    private var lastPosition = 0f

    /**
     * Estimates of unmeasured items change as items are measured. The change is carried as an error that
     * decays with scrolled distance relative to the distance left, so it is gone when the end arrives.
     */
    private fun smoothed(raw: Float, moved: Float): Float {
        val jump = !seen || kotlin.math.abs(moved) > 2 * viewport
        seen = true
        val shown = if (jump || raw == 0f) raw else {
            val cap = minOf(.004f * (position + raw), raw / 2f)
            val error = ((shownRemaining - moved - raw) * (1f - minOf(1f, kotlin.math.abs(moved) / (maxOf(raw, viewport) / 3f)))).coerceIn(-cap, cap)
            (raw + error).coerceAtLeast(0f)
        }
        shownRemaining = shown
        return shown
    }

    private fun record(index: Int, share: Float, first: Int) {
        val old = extent[index]
        if (old.isNaN()) { measuredCount++; measuredSum += share } else measuredSum += share - old
        extent[index] = share
        lineFirst[index] = first.let { if (it > index) index else it }
    }

    private fun reset(total: Int) {
        extent = FloatArray(total) { Float.NaN }
        lineFirst = IntArray(total) { -1 }
        measuredSum = 0.0; measuredCount = 0; seen = false
    }
}

/**
 * Relative scrollbar dragging (#704). Finger travel moves the thumb fraction by its delta; the list then
 * moves by the same share of the distance still ahead of it: `step / (1 - fraction)` of the remaining
 * content going down, `step / fraction` of the covered content going up. With steady estimates that is
 * content/track pixels per finger pixel. When estimates change mid-drag (rows loading or measuring) only
 * the rate of later steps changes, so the list never moves against the finger, a grab never jumps it,
 * and both ends arrive exactly with the thumb.
 */
internal class ScrollbarDrag {
    /** The finger-driven thumb fraction, read by placement and drawing. */
    var fraction by mutableFloatStateOf(0f); private set
    private var applied = 0f
    private var taken = 0f

    fun begin(fraction: Float) { this.fraction = fraction.coerceIn(0f, 1f); applied = this.fraction; taken = applied }
    fun move(delta: Float) { fraction = (fraction + delta).coerceIn(0f, 1f) }

    /** The list movement in pixels owed since the last call, from the model's current [position] and
     * [remaining]: zero for none, and an infinity when the thumb reached that end (the list goes exactly there).
     */
    fun take(position: Float, remaining: Float): Float {
        val from = applied
        val to = fraction
        taken = to
        return when {
            to == from -> 0f
            to > from -> if (to >= 1f) Float.POSITIVE_INFINITY else (to - from) / (1f - from) * remaining
            else -> if (to <= 0f) Float.NEGATIVE_INFINITY else (to - from) / from * position
        }
    }

    /** Marks the travel last returned by [take] as executed. Travel that is never applied (blocked input, a stale
     * generation) stays owed, so the thumb and the list cannot drift apart.
     */
    fun accept() { applied = taken }
}
