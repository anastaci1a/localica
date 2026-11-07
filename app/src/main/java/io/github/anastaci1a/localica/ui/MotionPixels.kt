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
import io.github.anastaci1a.localica.ui.engine.*

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
