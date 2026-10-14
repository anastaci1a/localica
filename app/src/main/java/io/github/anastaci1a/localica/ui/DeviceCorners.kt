package io.github.anastaci1a.localica.ui

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window-relative device geometry, shared by the player and drawer outline. */
internal data class DeviceCorners(val topLeft: Dp, val topRight: Dp, val bottomRight: Dp, val bottomLeft: Dp) {
    fun shape(from: Dp = 0.dp, progress: Float = 1f): AbsoluteRoundedCornerShape {
        fun radius(target: Dp) = from + (target - from) * progress.coerceIn(0f, 1f)
        return AbsoluteRoundedCornerShape(topLeft = radius(topLeft), topRight = radius(topRight),
            bottomRight = radius(bottomRight), bottomLeft = radius(bottomLeft))
    }
    val maximum get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)
}

@Composable
internal fun rememberDeviceCorners(): DeviceCorners {
    val view = LocalView.current
    val density = LocalDensity.current
    var corners by remember(view, density) { mutableStateOf(DeviceCorners(28.dp, 28.dp, 28.dp, 28.dp)) }
    DisposableEffect(view, density) {
        fun update() {
            if (Build.VERSION.SDK_INT >= 31) {
                val insets = view.rootWindowInsets ?: return
                fun corner(position: Int) = insets.getRoundedCorner(position)?.let { with(density) { it.radius.toDp() } } ?: 28.dp
                corners = DeviceCorners(corner(RoundedCorner.POSITION_TOP_LEFT), corner(RoundedCorner.POSITION_TOP_RIGHT),
                    corner(RoundedCorner.POSITION_BOTTOM_RIGHT), corner(RoundedCorner.POSITION_BOTTOM_LEFT))
            }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return corners
}
