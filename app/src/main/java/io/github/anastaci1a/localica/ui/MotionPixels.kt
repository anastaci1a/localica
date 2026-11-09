package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** Interpolating whole-pixel endpoints can leave float error such as 63.99998. */
internal const val MotionSnapTolerance = 1f / 256f

internal fun motionSnap(value: Float): Float {
    val whole = round(value)
    return if (abs(value - whole) <= MotionSnapTolerance) whole else value
}

internal fun motionSnap(rect: Rect) =
    Rect(motionSnap(rect.left), motionSnap(rect.top), motionSnap(rect.right), motionSnap(rect.bottom))

/**
 * Eased motion owns ONE float rectangle per frame. Whole-pixel layout is derived from its
 * edges, never from a separately rounded position and size: round(x) + round(w) can step back
 * by a pixel while x + w grows, which alternates an edge between rounding outcomes (#505, #510).
 * Each rounded edge is a monotone function of its float edge, and a rectangle on whole pixels
 * (rest and progress endpoints) maps exactly onto them with no partially covered edge pixel.
 *
 * Paths that need sub-pixel precision apply the remaining fraction as a layer transform
 * ([placeMotion]) or draw at the float rectangle ([drawMotionImage]); paths whose edges must
 * stay opaque (the player surface) use the whole-pixel bounds alone ([motionBounds]).
 */
internal data class MotionPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int,
    val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun motionPixelRect(rect: Rect): MotionPixelRect {
    val exact = motionSnap(rect)
    val left = exact.left.roundToInt()
    val top = exact.top.roundToInt()
    // A non-empty extent keeps a drawable pixel; its layer scale restores the exact size.
    fun far(edge: Float, start: Int, extent: Float) = edge.roundToInt().let {
        if (it <= start) if (extent > 0f) start + 1 else start else it }
    val right = far(exact.right, left, exact.width)
    val bottom = far(exact.bottom, top, exact.height)
    fun scale(extent: Float, pixels: Int) = if (pixels <= 0) 1f else motionScale(extent.coerceAtLeast(0f) / pixels)
    return MotionPixelRect(left, top, right, bottom, exact.left - left, exact.top - top,
        scale(exact.width, right - left), scale(exact.height, bottom - top))
}

private fun motionScale(value: Float) = if (abs(value - 1f) <= 1e-6f) 1f else value

/** Mirror a left-to-right rectangle inside a parent of [width], as placeRelative does. */
internal fun Rect.mirrored(width: Float) = Rect(width - right, top, width - left, bottom)

/** Measure at [rect]'s whole-pixel size; place to cover [rect] exactly (parent, absolute coordinates). */
internal fun Placeable.PlacementScope.placeMotion(placeable: Placeable, rect: MotionPixelRect) =
    placeable.placeWithLayer(rect.left, rect.top) {
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = rect.translationX; translationY = rect.translationY
        scaleX = rect.scaleX; scaleY = rect.scaleY
    }

internal fun MotionPixelRect.constraints() = Constraints.fixed(width, height)

/** Replaces offset(x, y).width(w).height(h), which rounds position and size independently.
 * Whole-pixel edges only: no fractional transform, so no partially covered edge pixel. */
internal fun Modifier.motionBounds(x: Dp, y: Dp, width: Dp, height: Dp): Modifier = layout { measurable, constraints ->
    val pixels = motionPixelRect(Rect(x.toPx(), y.toPx(), (x + width).toPx(), (y + height).toPx()))
    val placeable = measurable.measure(constraints.constrain(pixels.constraints()))
    layout(placeable.width, placeable.height) { placeable.placeRelative(pixels.left, pixels.top) }
}

/** Draw [srcSize] of [image] covering [rect] exactly; whole-pixel rectangles stay pixel-exact. */
internal fun DrawScope.drawMotionImage(image: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize,
    rect: Rect, alpha: Float = 1f) {
    if (srcSize.width <= 0 || srcSize.height <= 0) return
    val exact = motionSnap(rect)
    if (exact.width <= 0f || exact.height <= 0f) return
    translate(exact.left, exact.top) {
        scale(motionScale(exact.width / srcSize.width), motionScale(exact.height / srcSize.height), Offset.Zero) {
            drawImage(image, srcOffset = srcOffset, srcSize = srcSize, dstSize = srcSize, alpha = alpha)
        }
    }
}

/** The same whole-pixel edge rounding, with geometry read during measurement. */
internal fun Modifier.motionBounds(rect: androidx.compose.ui.unit.Density.() -> Rect): Modifier = layout { measurable, constraints ->
    val pixels = motionPixelRect(rect())
    val placeable = measurable.measure(constraints.constrain(pixels.constraints()))
    layout(placeable.width, placeable.height) { placeable.placeRelative(pixels.left, pixels.top) }
}
