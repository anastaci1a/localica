package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** Match Image's centered PainterModifier and BitmapPainter destination rounding.
 * Use the painter's ContentScale, not a second implementation of its crop policy.
 * The returned rectangle may extend beyond the measured cover; clipping and feathering
 * still belong to that cover. A letterbox is not a clamped image sample. */
internal fun playerBackgroundImageBounds(source: Size, cover: Rect, contentScale: ContentScale): Rect? {
    if (listOf(source.width, source.height, cover.width, cover.height).any { !it.isFinite() || it <= 0f } ||
        listOf(cover.left, cover.top, cover.right, cover.bottom).any { !it.isFinite() }) return null
    val scale = contentScale.computeScaleFactor(source, cover.size)
    val width = source.width * scale.scaleX
    val height = source.height * scale.scaleY
    if (listOf(width, height, cover.width, cover.height).any { !it.isFinite() || it <= 0f || it >= Int.MAX_VALUE.toFloat() }) return null
    val image = IntSize(width.roundToInt(), height.roundToInt())
    if (image.width == 0 || image.height == 0) return null
    val offset = Alignment.Center.align(image,
        IntSize(cover.width.roundToInt(), cover.height.roundToInt()), LayoutDirection.Ltr)
    val left = cover.left + offset.x
    val top = cover.top + offset.y
    return Rect(left, top, left + image.width, top + image.height)
}
