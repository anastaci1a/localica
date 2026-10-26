package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class PinchAtlasLayout(val size: IntSize, val origins: List<IntOffset>)
private data class AtlasSpan(val start: Long, val end: Long, val top: Long)

/** Keep the atlas two-dimensional instead of making a viewport-wide texture strip.
 * Regions retain their exact pixels and input identities, with one clear pixel around each. */
internal fun packPinchAtlas(sizes: List<IntSize>, minimumWidth: Int): PinchAtlasLayout {
    require(sizes.isNotEmpty() && minimumWidth >= 0)
    require(sizes.all { it.width > 0 && it.height > 0 })
    val area = sizes.fold(0L) { sum, size ->
        Math.addExact(sum, (size.width.toLong() + 2) * (size.height.toLong() + 2))
    }
    val width = maxOf(minimumWidth.toLong() + 2, sizes.maxOf { it.width.toLong() + 2 },
        ceil(sqrt(area.toDouble())).toLong())
    require(width <= Int.MAX_VALUE)
    val origins = MutableList(sizes.size) { IntOffset.Zero }
    var skyline = listOf(AtlasSpan(0, width, 0))
    var height = 0L
    // Fill beside a tall header instead of reserving its height across a full shelf.
    for (index in sizes.indices.sortedByDescending { sizes[it].height }) {
        val size = sizes[index]
        val paddedWidth = size.width.toLong() + 2
        val paddedHeight = size.height.toLong() + 2
        var x = 0L
        var y = Long.MAX_VALUE
        for (span in skyline) {
            val right = span.start + paddedWidth
            if (right > width) continue
            val top = skyline.filter { it.start < right && it.end > span.start }.maxOf { it.top }
            if (top < y) { x = span.start; y = top }
        }
        val right = x + paddedWidth
        val bottom = Math.addExact(y, paddedHeight)
        require(bottom <= Int.MAX_VALUE)
        origins[index] = IntOffset((x + 1).toInt(), (y + 1).toInt())
        val next = mutableListOf<AtlasSpan>()
        var inserted = false
        for (span in skyline) {
            if (span.end <= x || span.start >= right) next += span else {
                if (span.start < x) next += AtlasSpan(span.start, x, span.top)
                if (!inserted) { next += AtlasSpan(x, right, bottom); inserted = true }
                if (span.end > right) next += AtlasSpan(right, span.end, span.top)
            }
        }
        val merged = mutableListOf<AtlasSpan>()
        for (span in next) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.end == span.start && previous.top == span.top)
                merged[merged.lastIndex] = previous.copy(end = span.end)
            else merged += span
        }
        skyline = merged
        height = maxOf(height, bottom)
    }
    // Bitmap byte counts and the legacy packed RGBA buffer are Int-sized.
    require(height <= Int.MAX_VALUE && width <= Int.MAX_VALUE / 4L / height)
    return PinchAtlasLayout(IntSize(width.toInt(), height.toInt()), origins.toList())
}
