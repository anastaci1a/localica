package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect

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

internal inline fun averageGlassColor(sample: (Int, Int) -> Int): androidx.compose.ui.graphics.Color {
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (y in 0 until 8) for (x in 0 until 8) {
        val pixel = sample(x, y)
        val a = (pixel ushr 24) / 255f
        red += (pixel ushr 16 and 255) / 255f * a
        green += (pixel ushr 8 and 255) / 255f * a
        blue += (pixel and 255) / 255f * a
        alpha += a
    }
    return if (alpha == 0f) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.ui.graphics.Color(red / alpha, green / alpha, blue / alpha, alpha / 64)

}

internal fun glassMorphSpring(speed: Float) = androidx.compose.animation.core.spring<Float>(1f, 90f * speed * speed, visibilityThreshold = .0001f)
