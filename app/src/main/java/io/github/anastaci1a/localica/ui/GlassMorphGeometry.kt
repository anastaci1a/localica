package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect

/** Quiet controls finish all shape changes together before their temporary lens fades. */
internal fun glassMorphGeometryProgress(progress: Float, quiet: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
}

/** One rectangle owns the popup's position and clip; never round position and size separately. */
internal fun glassMorphPixelBounds(rect: Rect, viewportWidth: Int, viewportHeight: Int): MotionPixelRect {
    val width = rect.width.coerceIn(1f, viewportWidth.toFloat())
    val height = rect.height.coerceIn(1f, viewportHeight.toFloat())
    return motionPixelRect(Rect(rect.left, rect.top, rect.left + width, rect.top + height))
}
