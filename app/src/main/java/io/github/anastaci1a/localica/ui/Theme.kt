package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFB7432B), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD0), onPrimaryContainer = Color(0xFF431106),
    secondaryContainer = Color(0xFFF2DBD3), onSecondaryContainer = Color(0xFF431106),
    background = Color(0xFFFAF8F5), surface = Color(0xFFFAF8F5),
    surfaceContainer = Color(0xFFF0ECE6), surfaceVariant = Color(0xFFEAE5DD),
    onSurface = Color(0xFF242723), onSurfaceVariant = Color(0xFF64675F),
    outlineVariant = Color(0xFFDCD8D0),
)

@Composable
fun LocalicaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors,
        shapes = androidx.compose.material3.Shapes(small = Corners.card, medium = Corners.menu, large = Corners.largeArtwork, extraLarge = Corners.panel),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content)
    }
}
