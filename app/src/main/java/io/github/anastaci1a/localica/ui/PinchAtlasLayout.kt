package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class PinchAtlasLayout(val size: IntSize, val origins: List<IntOffset>)

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
    var x = 1L
    var y = 1L
    var rowHeight = 0L
    // A full-height header cannot waste the height of a shelf of small row fragments.
    for (index in sizes.indices.sortedByDescending { sizes[it].height }) {
        val size = sizes[index]
        if (x + size.width + 1 > width) {
            x = 1
            y = Math.addExact(y, rowHeight + 2)
            rowHeight = 0
        }
        require(y <= Int.MAX_VALUE)
        origins[index] = IntOffset(x.toInt(), y.toInt())
        x += size.width.toLong() + 2
        rowHeight = maxOf(rowHeight, size.height.toLong())
    }
    val height = Math.addExact(y, rowHeight + 1)
    // Bitmap byte counts and the legacy packed RGBA buffer are Int-sized.
    require(height <= Int.MAX_VALUE && width <= Int.MAX_VALUE / 4L / height)
    return PinchAtlasLayout(IntSize(width.toInt(), height.toInt()), origins.toList())
}
