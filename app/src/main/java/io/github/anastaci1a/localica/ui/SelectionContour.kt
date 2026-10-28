package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The same primitives describe the visible union and its optical boundary.
 * Axis intersections retain subpixel gaps even when both grid endpoints are inside.
 * No sampled mask, hull filling or optical field is substituted here. */
internal data class SelectionContourBody(val bounds: Rect, val radius: Float) {
    fun interval(horizontal: Boolean, line: Double): SelectionInterval? {
        val near = (if (horizontal) bounds.top else bounds.left).toDouble()
        val far = (if (horizontal) bounds.bottom else bounds.right).toDouble()
        if (line < near || line > far) return null
        val low = (if (horizontal) bounds.left else bounds.top).toDouble()
        val high = (if (horizontal) bounds.right else bounds.bottom).toDouble()
        val r = radius.toDouble()
        val inward = min(line - near, far - line)
        val inset = if (inward < r) r - sqrt(max(0.0, r * r - (r - inward) * (r - inward))) else 0.0
        return SelectionInterval(low + inset, high - inset)
    }
}
internal data class SelectionInterval(val start: Double, val end: Double)

internal class SelectionContour(val bodies: List<SelectionContourBody>) {
    val bounds: Rect? = bodies.firstOrNull()?.bounds?.let { first ->
        bodies.drop(1).fold(first) { a, b -> Rect(min(a.left, b.bounds.left), min(a.top, b.bounds.top),
            max(a.right, b.bounds.right), max(a.bottom, b.bounds.bottom)) }
    }

    fun intervals(horizontal: Boolean, line: Double): List<SelectionInterval> {
        val ordered = bodies.mapNotNull { it.interval(horizontal, line) }.sortedBy { it.start }
        val result = ArrayList<SelectionInterval>(ordered.size)
        ordered.forEach { next ->
            val previous = result.lastOrNull()
            if (previous != null && next.start <= previous.end) {
                result[result.lastIndex] = SelectionInterval(previous.start, max(previous.end, next.end))
            } else result.add(next)
        }
        return result
    }

    /** Null means the whole segment is inside; otherwise first exit fraction.
     * The start must be inside. A hole cannot be bridged by endpoint membership. */
    fun firstExit(horizontal: Boolean, line: Double, start: Double, end: Double): Double? {
        val interval = intervals(horizontal, line).firstOrNull { start >= it.start && start <= it.end }
            ?: error("Segment must start inside the selection contour")
        if (end >= interval.start && end <= interval.end) return null
        val boundary = if (end > start) interval.end else interval.start
        return (boundary - start) / (end - start)
    }

    /** Largest permitted reference corner on the actual union hull. Each rounded
     * primitive constrains a hull corner by radius + a + b + sqrt(2ab).
     * Bridges participate too: their sharp corners may be the limiting points.
     * This chooses a containing reference, never changes the visible contour. */
    fun referenceGeometry(desiredCorner: Float): LensGeometry? {
        val hull = bounds ?: return null
        var corner = min(desiredCorner.toDouble(), min(hull.width, hull.height).toDouble() / 2).coerceAtLeast(0.0)
        bodies.forEach { body ->
            val x = doubleArrayOf(body.bounds.left.toDouble() - hull.left, hull.right.toDouble() - body.bounds.right)
            val y = doubleArrayOf(body.bounds.top.toDouble() - hull.top, hull.bottom.toDouble() - body.bounds.bottom)
            x.forEach { a -> y.forEach { b -> corner = min(corner, body.radius + a + b + sqrt(2 * a * b)) } }
        }
        val rounded = corner.toFloat()
        val contained = if (rounded.toDouble() > corner) Math.nextDown(rounded) else rounded
        return LensGeometry(hull, contained)
    }
}

internal fun selectionContour(regions: List<SelectionRegion>, gap: Float): SelectionContour {
    val bodies = regions.mapTo(ArrayList()) { SelectionContourBody(it.bounds, mediaItemCornerRadius(it.bounds, gap)) }
    regions.forEachIndexed { i, a -> regions.drop(i + 1).forEach { b ->
        val xGap = max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right)
        val yGap = max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom)
        if (xGap > gap || yGap > gap || (xGap < 0 && yGap < 0)) return@forEach
        if (xGap < 0 || yGap < 0) {
            val r = gap * 1.5f
            val bridge = if (xGap < 0) Rect(max(a.bounds.left, b.bounds.left),
                min(a.bounds.bottom, b.bounds.bottom) - r,
                min(a.bounds.right, b.bounds.right), max(a.bounds.top, b.bounds.top) + r)
            else Rect(min(a.bounds.right, b.bounds.right) - r, max(a.bounds.top, b.bounds.top),
                max(a.bounds.left, b.bounds.left) + r, min(a.bounds.bottom, b.bounds.bottom))
            bodies.add(SelectionContourBody(bridge, 0f))
        }
    } }
    return SelectionContour(bodies)
}
