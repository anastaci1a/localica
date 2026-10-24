package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

internal const val ArtworkMaximumZoom = 6f

internal data class ArtworkTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/** ContentScale.Fit leaves the short axis centered until the scaled image fills it. */
internal fun fittedArtworkSize(viewport: Size, image: Size): Size {
    if (viewport.width <= 0f || viewport.height <= 0f || image.width <= 0f || image.height <= 0f) return Size.Zero
    val fit = min(viewport.width / image.width, viewport.height / image.height)
    return Size(image.width * fit, image.height * fit)
}

internal data class ArtworkPanBounds(val x: Float, val y: Float) {
    // Canonical zero matters: Offset equality compares packed float bits, including -0f.
    fun clamp(offset: Offset) = Offset(if (x == 0f) 0f else offset.x.coerceIn(-x, x),
        if (y == 0f) 0f else offset.y.coerceIn(-y, y))
}

internal fun artworkPanBounds(viewport: Size, image: Size, scale: Float): ArtworkPanBounds {
    val fitted = fittedArtworkSize(viewport, image)
    return ArtworkPanBounds(max(0f, (fitted.width * scale - viewport.width) / 2f),
        max(0f, (fitted.height * scale - viewport.height) / 2f))
}

/** Keep the image point under the old centroid while zoom and pan happen together. */
internal fun transformArtwork(current: ArtworkTransform, viewport: Size, image: Size,
    zoom: Float, pan: Offset, centroid: Offset): ArtworkTransform {
    val scale = (current.scale * zoom).coerceIn(1f, ArtworkMaximumZoom)
    val pivot = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    val offset = pivot + (current.offset - pivot) * (scale / current.scale) + pan
    return ArtworkTransform(scale, artworkPanBounds(viewport, image, scale).clamp(offset))
}

internal data class ArtworkPanMotion(val offset: Offset, val velocity: Offset) {
    val moving get() = velocity != Offset.Zero
}

/** Exact exponential integration is frame-rate independent. An edge stops only its own
 * velocity component, so diagonal releases slide along the remaining edge, never bounce.
 */
internal fun advanceArtworkPan(motion: ArtworkPanMotion, bounds: ArtworkPanBounds,
    seconds: Float, stopSpeed: Float): ArtworkPanMotion {
    val friction = 5f
    val decay = exp(-friction * seconds.coerceAtLeast(0f))
    val raw = motion.offset + motion.velocity * ((1f - decay) / friction)
    val offset = bounds.clamp(raw)
    val velocity = motion.velocity * decay
    return ArtworkPanMotion(offset, Offset(
        if (raw.x != offset.x || offset.x >= bounds.x && velocity.x > 0f ||
            offset.x <= -bounds.x && velocity.x < 0f || abs(velocity.x) < stopSpeed) 0f else velocity.x,
        if (raw.y != offset.y || offset.y >= bounds.y && velocity.y > 0f ||
            offset.y <= -bounds.y && velocity.y < 0f || abs(velocity.y) < stopSpeed) 0f else velocity.y,
    ))
}
