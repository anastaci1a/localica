package io.github.anastaci1a.localica.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer

/** One retained owner. Ordinary labels and controls observe these fields in draw.
 * Dialogs and legacy glass controls opt into the live Material scheme separately. */
@Stable
internal class PlayerDrawTheme(initial: ColorScheme) {
    var colors by mutableStateOf(initial)
        private set
    val foreground = ColorProducer { colors.onSurface }
    val secondary = ColorProducer { colors.onSurfaceVariant }
    fun present(value: ColorScheme) { colors = value }
}
internal val LocalPlayerDrawTheme = staticCompositionLocalOf<PlayerDrawTheme?> { null }

/** Small legacy islands retain the exact current Material appearance. */
@Composable
internal fun PlayerMaterialTheme(theme: PlayerDrawTheme, content: @Composable () -> Unit) {
    val colors = theme.colors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface, content = content)
    }
}
