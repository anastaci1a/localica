package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
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

internal fun motionScale(value: Float) = if (abs(value - 1f) <= 1e-6f) 1f else value

/** D16: the frame's one float rectangle → four rounded edges. */
internal fun ShapeFrame.pixelRect(): MotionPixelRect = motionPixelRect(bounds)

/** D23: a drawable popup extent has at least one physical pixel. */
internal fun popupPixelBounds(rect: Rect)=motionPixelRect(Rect(rect.left,rect.top,
    rect.left+rect.width.coerceAtLeast(1f),rect.top+rect.height.coerceAtLeast(1f)))
