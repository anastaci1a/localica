package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext
import io.github.anastaci1a.localica.ui.layout.expansionReferenceTravel
import io.github.anastaci1a.localica.ui.spec.*

/** One live settings and measured-window record for the screen and its sibling dialog hosts. */
internal class LocalicaUiRootState(settings: InterfaceSettings) {
    val settings = mutableStateOf(settings)
    var size by mutableStateOf(IntSize.Zero)
    val window: IntSize
        @Composable get() = size.takeIf { it.height > 0 } ?: LocalWindowInfo.current.containerSize
}

internal val LocalLocalicaUiRoot = staticCompositionLocalOf<LocalicaUiRootState?> { null }

@Composable
internal fun rememberLocalicaUiRoot(): LocalicaUiRootState {
    val context = LocalContext.current
    return remember(context) { LocalicaUiRootState(InterfacePreferences(context).read()) }
}

@Composable
internal fun LocalicaUiRoot(state: LocalicaUiRootState = rememberLocalicaUiRoot(), content: @Composable () -> Unit) {
    if (LocalLocalicaUiRoot.current === state) {
        content()
        return
    }
    val settings by state.settings
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val density = LocalDensity.current
    val window = state.window
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val refreshHz = rememberPeakRefreshHz()
    val referenceTravel = expansionReferenceTravel(playerBasis(density, window, navigationInset, StockUiSpec.clearance.getValue(Clearance.BottomChrome)))
    val resolvedSpec = remember(settings.glass, settings.reducedMotion, settings.fasterMotion, referenceTravel, window, density) {
        uiSpec(settings, ExpansionGeometryDomain(referenceTravel, maxOf(1f, kotlin.math.hypot(window.width.toFloat(), window.height.toFloat())), 1f / 24), density)
    }
    ProvideLocalicaEngineContext(refreshHz, referenceTravel, resolvedSpec, durationScale) {
        CompositionLocalProvider(LocalLocalicaUiRoot provides state, LocalUiSpec provides resolvedSpec, content = content)
    }
}

/** U11.00: peak display refresh, re-published when the display changes (the engine's two-frame floor). */
@Composable private fun rememberPeakRefreshHz(): Float {
    val view = androidx.compose.ui.platform.LocalView.current
    fun peak() = (view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f).coerceAtLeast(1f)
    var refreshHz by remember(view) { mutableFloatStateOf(peak()) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { refreshHz = peak() } }
        displays.registerDisplayListener(listener, null); onDispose { displays.unregisterDisplayListener(listener) }
    }
    return refreshHz
}
