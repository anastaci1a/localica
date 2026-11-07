package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import kotlin.math.ceil
import kotlin.math.floor

/** A small top clip can move the remaining tint's midpoint without moving the card.
 * Use the independently measured full height and an uncovered optical edge (#547).
 * Both hidden edges leave the frame unplaceable; the caller retains its existing cap. */
internal fun galleryHighlightCenter(pixels: PixelMap, contour: Rect, covers: List<Rect>): Float {
    // This narrow central strip stays on the straight edges of the rounded card.
    val left = floor(contour.left + contour.width * .45f).toInt().coerceAtLeast(0)
    val right = ceil(contour.left + contour.width * .55f).toInt().coerceAtMost(pixels.width)
    if (right - left < 3) return Float.NaN
    val top = floor(contour.top - contour.height).toInt().coerceAtLeast(0)
    val bottom = ceil(contour.bottom + contour.height).toInt().coerceAtMost(pixels.height)
    val rows = (top until bottom).filter { y ->
        var matches = 0
        for (x in left until right) {
            val color = pixels[x, y]
            if (color.red - color.green > .07f && color.blue - color.green > .07f && ++matches == 3)
                return@filter true
        }
        false
    }
    if (rows.isEmpty()) return Float.NaN
    fun clear(edge: Float): Boolean {
        val band = Rect(left.toFloat(), edge - 2f, right.toFloat(), edge + 2f)
        return covers.none { it.overlaps(band) }
    }
    val upper = clear(contour.top)
    val lower = clear(contour.bottom)
    return when {
        upper && lower -> (rows.first() + rows.last() + 1) / 2f
        upper -> rows.first() + contour.height / 2f
        lower -> rows.last() + 1 - contour.height / 2f
        else -> Float.NaN
    }
}

/** Hidden optical edges cannot establish a new held center. Preserve the last
 * observed optical anchor until a placeable frame arrives; keep the four-pixel bound. */
internal fun pinchReleaseAnchorRange(held: Float, lastVisible: Float, final: Float): ClosedFloatingPointRange<Float> {
    require(lastVisible.isFinite() && final.isFinite()) { "Release bounds require observed finite anchors" }
    val start = if (held.isFinite()) held else lastVisible
    return (minOf(start, final) - 4f)..(maxOf(start, final) + 4f)
}
