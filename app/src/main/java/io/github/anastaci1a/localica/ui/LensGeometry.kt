package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import kotlin.math.*

internal data class LensGeometry(val bodies: List<RoundRect>) {
    val bounds = when (bodies.size) {
        0 -> Rect.Zero
        1 -> bodies.single().boundingRect
        else -> Rect(bodies.minOf { it.left }, bodies.minOf { it.top },
            bodies.maxOf { it.right }, bodies.maxOf { it.bottom })
    }
    val span get() = min(bounds.width, bounds.height) / 2f
    val corner get() = bodies.single().topLeftCornerRadius.x
}

internal fun LensGeometry(bounds: Rect, corner: Float) =
    LensGeometry(listOf(RoundRect(bounds, CornerRadius(corner.coerceIn(0f, bounds.size.minDimension / 2f)))))

/** Compose 58 fields contain negative-inside distance, outward normal and coverage.
 * The caller floors the smoothing value before submitting it. */
internal const val LensFieldUnionShader = """
    half4 sdfUnion(half4 a, half4 element, half smoothing) {
        half4 b = element.w == half(0) ? half4(10000, 0, 0, 0) : element;
        half alignment = clamp(half(0.5) - half(dot(a.yz, b.yz) * half(0.5)), half(0), half(1));
        half k = half(alignment * smoothing);
        half h = clamp(half(half(half(b.x - a.x) * half(0.5)) / k) + half(0.5), half(0), half(1));
        half correction = half(half(k * h) * half(half(1) - h));
        return half4(half(mix(float(b.x), float(a.x), float(h)) - float(correction)), mix(b.yz, a.yz, h), half(1));
    }
"""

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
