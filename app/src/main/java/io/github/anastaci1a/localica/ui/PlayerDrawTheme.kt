package io.github.anastaci1a.localica.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp

/** Values accepted by composition. Events never sample a later, unapplied animation value. */
internal data class PlayerThemePresentation(val base: ColorScheme, val lightMix: Float,
    val foreground: Color, val playerInk: Color, val progress: Float,
    val bottom: Color, val middle: Color, val top: Color) {
    fun surface() = lerp(base.surface, bottom, progress)
    fun container() = lerp(base.surfaceContainer, middle, progress)
    fun colors() = glassPanelColors(base, lightMix).copy(onSurface = foreground,
        onSurfaceVariant = foreground.copy(alpha = .72f), primary = lerp(base.primary, playerInk, progress),
        surface = surface(), surfaceContainer = container(),
        surfaceContainerHigh = lerp(base.surfaceContainerHigh, middle, progress),
        surfaceContainerHighest = lerp(base.surfaceContainerHighest, top, progress))
}

/** Continuous presentation is read in draw or on an event; Material observes only discrete inputs. */
@Stable
internal class PlayerDrawTheme(initial: ColorScheme) {
    private var explicit by mutableStateOf(initial)
    private var applied by mutableStateOf<PlayerThemePresentation?>(null)
    var materialColors by mutableStateOf(initial)
        private set
    val colors: ColorScheme get() = applied?.colors() ?: explicit
    val panelColors: ColorScheme get() = applied?.let { glassPanelColors(it.base, it.lightMix) } ?: explicit
    val foreground = ColorProducer { applied?.foreground ?: explicit.onSurface }
    val secondary = ColorProducer { applied?.foreground?.copy(alpha = .72f) ?: explicit.onSurfaceVariant }
    val surface = ColorProducer { applied?.surface() ?: explicit.surface }
    val container = ColorProducer { applied?.container() ?: explicit.surfaceContainer }
    val error = ColorProducer { applied?.let { glassPanelErrorColor(it.base, it.lightMix, false) } ?: explicit.error }
    val onError = ColorProducer { applied?.let { glassPanelErrorColor(it.base, it.lightMix, true) } ?: explicit.onError }
    /** Explicit scheme adapter, including independently mounted loading fixtures. */
    fun present(value: ColorScheme) { applied = null; explicit = value; materialColors = value }
    fun present(value: PlayerThemePresentation) { applied = value }
    fun presentMaterial(value: ColorScheme) { materialColors = value }
}
internal val LocalPlayerDrawTheme = staticCompositionLocalOf<PlayerDrawTheme?> { null }

@Composable
internal fun PlayerMaterialTheme(theme: PlayerDrawTheme, content: @Composable () -> Unit) {
    val colors = theme.materialColors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface,
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(color = Color.Unspecified),
            content = content)
    }
}

/** Only mounted outer viewer/configure consumers need the historical continuous panel scheme. */
@Composable
internal fun PlayerPanelMaterialTheme(theme: PlayerDrawTheme, content: @Composable () -> Unit) {
    val colors = theme.panelColors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalPlayerDrawTheme provides null,
            androidx.compose.material3.LocalContentColor provides colors.onSurface,
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(color = Color.Unspecified),
            content = content)
    }
}
