package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import kotlin.math.*

internal data class LensGeometry(val bounds: Rect, val corner: Float, private val outlines: List<RoundRect>? = null) {
    val span get() = min(bounds.width, bounds.height) / 2f
    val bodies get() = outlines ?: listOf(RoundRect(bounds, CornerRadius(corner.coerceIn(0f, span))))

    companion object {
        fun fromBodies(bodies: List<RoundRect>): LensGeometry {
            val bounds = if (bodies.isEmpty()) Rect.Zero else Rect(bodies.minOf { it.left }, bodies.minOf { it.top },
                bodies.maxOf { it.right }, bodies.maxOf { it.bottom })
            return LensGeometry(bounds, 0f, bodies)
        }
    }
}

internal data class LiveLensFrame(val geometry: LensGeometry, val scale: Offset, val drift: Offset)

/** Fit the four adjacent radius sums before applying body strain. */
internal fun RoundRect.fittedLensCorners(): RoundRect {
    fun CornerRadius.squareIfCollapsed() = if (x > 0f && y > 0f) this else CornerRadius.Zero
    val tl = topLeftCornerRadius.squareIfCollapsed()
    val tr = topRightCornerRadius.squareIfCollapsed()
    val br = bottomRightCornerRadius.squareIfCollapsed()
    val bl = bottomLeftCornerRadius.squareIfCollapsed()
    fun fit(length: Double, a: Float, b: Float) = length / (a.toDouble() + b)
    val width = right.toDouble() - left
    val height = bottom.toDouble() - top
    val scale = minOf(1.0, fit(width, tl.x, tr.x), fit(height, tr.y, br.y),
        fit(width, br.x, bl.x), fit(height, bl.y, tl.y)).toFloat()
    fun CornerRadius.scaled() = CornerRadius(x * scale, y * scale)
    return RoundRect(left, top, right, bottom, tl.scaled(), tr.scaled(), br.scaled(), bl.scaled())
}

/** Background refraction, in dp. Each profile displaces along the inward gradient by
 * amount * (1 - sqrt(t(2 - t))), t = inside / height. The outer sample mixes in only
 * within the outermost dp of the edge. */
internal object GlassRefraction {
    const val InnerAmount = -38.4f
    const val InnerHeight = 12f
    const val OuterAmount = 9.6f
    const val OuterHeight = 6f
    const val OuterOpacity = .3f
    const val OuterDistance = -1f
    const val OuterDistanceEnd = -.5f
}

/** Precision noise in coordinate subtraction must not rebuild a silhouette while
 * its content simply translates. The origin retains full subpixel precision. */
internal fun localLensRegions(regions: List<SelectionRegion>, origin: Offset,
    previous: List<SelectionRegion>? = null): List<SelectionRegion> {
    var result: MutableList<SelectionRegion>? = if(previous?.size==regions.size)null else ArrayList(regions.size)
    fun stable(value: Float) = round(value * 64f) / 64f
    for(i in regions.indices) {
        val region=regions[i];val bounds=region.bounds
        val l=stable(bounds.left-origin.x);val t=stable(bounds.top-origin.y)
        val r=stable(bounds.right-origin.x);val b=stable(bounds.bottom-origin.y)
        val old=previous?.getOrNull(i)
        if(old!=null && old.bounds.left.toBits()==l.toBits() && old.bounds.top.toBits()==t.toBits() &&
            old.bounds.right.toBits()==r.toBits() && old.bounds.bottom.toBits()==b.toBits() && old.corner==region.corner) {
            result?.add(old)
        } else {
            if(result==null)result=ArrayList<SelectionRegion>(regions.size).also { next -> for(j in 0 until i)next.add(previous!![j]) }
            result.add(SelectionRegion(Rect(l,t,r,b),region.corner))
        }
    }
    return result ?: previous ?: emptyList()
}

/** Exact common case, not a bounding-hull approximation: one item or an aligned
 * contiguous chain whose rectangular bridges form one rounded outline. */
internal fun simpleLensGeometry(regions: List<SelectionRegion>, gap: Float): LensGeometry? {
    if (regions.isEmpty() || !gap.isFinite() || gap < 0f || regions.any {
        val r = it.bounds
        !listOf(r.left, r.top, r.right, r.bottom, r.width, r.height).all { value -> value.isFinite() } ||
            r.width <= 0f || r.height <= 0f
    }) return null
    val sorted = regions.sortedBy { it.bounds.top }
    val first = sorted.first().bounds
    val radius = sorted.first().radius(gap)
    if (sorted.all { it.bounds.left == first.left && it.bounds.right == first.right && it.bounds.height >= 2 * radius && it.radius(gap) == radius } &&
        sorted.zipWithNext().all { (a, b) -> b.bounds.top - a.bounds.bottom in 0f..gap }) {
        return LensGeometry(Rect(first.left, first.top, first.right, sorted.last().bounds.bottom), radius)
    }
    val row = regions.sortedBy { it.bounds.left }
    val left = row.first().bounds
    val rowRadius = row.first().radius(gap)
    // The existing contour bridge extends gap*1.5 into BOTH neighbors. Keep
    // those shoulders inside each item's straight core, never its outside caps.
    // This deliberately declines narrow/unequal-radius cases rather than routing
    // a connected shape through its merely enclosing rectangle.
    val minimumWidth = gap * 1.5f * 2f
    if (!minimumWidth.isFinite() || row.any { it.bounds.top != left.top || it.bounds.bottom != left.bottom ||
        it.bounds.width < minimumWidth || it.radius(gap) != rowRadius }) return null
    if (row.zipWithNext().any { (a, b) -> b.bounds.left - a.bounds.right !in 0f..gap }) return null
    return LensGeometry(Rect(left.left, left.top, row.last().bounds.right, left.bottom), rowRadius)
}
