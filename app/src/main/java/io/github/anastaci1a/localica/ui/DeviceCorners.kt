package io.github.anastaci1a.localica.ui

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window-relative physical geometry for the expanded player, never an app panel radius. */
internal data class DeviceCorners(val topLeft: Dp = 0.dp, val topRight: Dp = 0.dp, val bottomRight: Dp = 0.dp, val bottomLeft: Dp = 0.dp) {
    fun shape(from: Dp = 0.dp, progress: Float = 1f): AbsoluteRoundedCornerShape {
        fun radius(target: Dp) = from + (target - from) * progress.coerceIn(0f, 1f)
        return AbsoluteRoundedCornerShape(topLeft = radius(topLeft), topRight = radius(topRight),
            bottomRight = radius(bottomRight), bottomLeft = radius(bottomLeft))
    }
    val maximum get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Missing geometry is square: never invent a cutout on a rectangular window. */
internal fun deviceCorners(insets: WindowInsets?, density: Density): DeviceCorners {
    if (Build.VERSION.SDK_INT < 31 || insets == null) return DeviceCorners()
    fun corner(position: Int) = insets.getRoundedCorner(position)?.let { with(density) { it.radius.toDp() } } ?: 0.dp
    return DeviceCorners(corner(RoundedCorner.POSITION_TOP_LEFT), corner(RoundedCorner.POSITION_TOP_RIGHT),
        corner(RoundedCorner.POSITION_BOTTOM_RIGHT), corner(RoundedCorner.POSITION_BOTTOM_LEFT))
}

@Composable
internal fun rememberDeviceCorners(): DeviceCorners {
    val view = LocalView.current
    val density = LocalDensity.current
    var corners by remember(view, density) { mutableStateOf(DeviceCorners()) }
    DisposableEffect(view, density) {
        fun update() { corners = deviceCorners(view.rootWindowInsets, density) }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return corners
}
