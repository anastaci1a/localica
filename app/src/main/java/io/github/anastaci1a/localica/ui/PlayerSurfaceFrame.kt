package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.engine.MotionPixelRect
import io.github.anastaci1a.localica.ui.engine.motionPixelRect

internal fun playerArtworkCornerRadius(progress: Float, backgroundProgress: Float): Dp {
    val p = progress.coerceIn(0f, 1f)
    return (8f + 16f * p).dp * (1f - p * backgroundProgress.coerceIn(0f, 1f))
}

/** One placement/contour for the player and overlays that must remain inside it. */
internal data class PlayerSurfaceFrame(val x: Dp, val y: Dp, val width: Dp, val height: Dp,
    val shape: AbsoluteRoundedCornerShape, val maximumCorner: Dp) {
    /** The whole-pixel bounds [motionBounds] lays the surface out at (left-to-right). */
    fun pixels(density: Density): MotionPixelRect = with(density) {
        motionPixelRect(androidx.compose.ui.geometry.Rect(x.toPx(), y.toPx(), (x + width).toPx(), (y + height).toPx()))
    }
    fun windowShape(): Shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            // Match the surface's motionBounds edges, not independent float bounds.
            val pixels = pixels(density)
            val outline = shape.createOutline(Size(pixels.width.toFloat(), pixels.height.toFloat()), layoutDirection, density)
            return Outline.Generic(Path().apply {
                addOutline(outline)
                translate(Offset(pixels.left.toFloat(), pixels.top.toFloat()))
            })
        }
    }
}

internal fun playerSurfaceFrame(width: Dp, height: Dp, navigationInset: Dp, bottomChrome: Dp,
    progress: Float, corners: DeviceCorners): PlayerSurfaceFrame {
    val p = progress.coerceIn(0f, 1f)
    val cornerProgress = ((p - .8f) / .2f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val miniCorner = CompactControlSize / 2
    return PlayerSurfaceFrame(12.dp * (1f - p),
        (height - navigationInset - (CompactControlSize + bottomChrome)) * (1f - p),
        width - 24.dp * (1f - p), CompactControlSize + (height - CompactControlSize) * p,
        corners.shape(miniCorner, cornerProgress), miniCorner * (1f - cornerProgress) + corners.maximum * cornerProgress)
}

/** Keep Queue in window coordinates and out of the player's backdrop recording. */
@Composable
internal fun BoxWithConstraintsScope.PlayerClippedOverlay(progress: () -> Float, bottomChrome: Dp,
    content: @Composable () -> Unit) {
    val width = maxWidth
    val height = maxHeight
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val corners = rememberDeviceCorners()
    Box(Modifier.fillMaxSize().graphicsLayer {
        shape = playerSurfaceFrame(width, height, inset, bottomChrome, progress(), corners).windowShape()
        clip = true
    }) { content() }
}
