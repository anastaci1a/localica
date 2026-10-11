package io.github.anastaci1a.localica.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Match system controls to the surface beneath them without a pale navigation-bar scrim. */
@Composable
internal fun PlayerSystemBars(dark: Boolean) {
    val view = LocalView.current
    val activity = view.context.activity() ?: return
    DisposableEffect(activity, dark) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        val statusWasLight = controller.isAppearanceLightStatusBars
        val navigationWasLight = controller.isAppearanceLightNavigationBars
        val contrastWasEnforced = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        if (Build.VERSION.SDK_INT >= 29 && dark) window.isNavigationBarContrastEnforced = false
        onDispose {
            controller.isAppearanceLightStatusBars = statusWasLight
            controller.isAppearanceLightNavigationBars = navigationWasLight
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = contrastWasEnforced
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
