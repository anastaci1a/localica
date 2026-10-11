package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF25262A), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E1E4), onPrimaryContainer = Color(0xFF202124),
    secondary = Color(0xFF5E6066), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7EA), onSecondaryContainer = Color(0xFF242528),
    tertiary = Color(0xFF5E6066), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E7EA), onTertiaryContainer = Color(0xFF242528),
    background = Color(0xFFF9F9FB), surface = Color(0xFFF9F9FB),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F5F7),
    surfaceContainer = Color(0xFFF0F0F3), surfaceContainerHigh = Color(0xFFEAEAED),
    surfaceContainerHighest = Color(0xFFE4E4E8), surfaceVariant = Color(0xFFE4E4E8),
    onBackground = Color(0xFF202124), onSurface = Color(0xFF202124), onSurfaceVariant = Color(0xFF61636A),
    outline = Color(0xFF777980), outlineVariant = Color(0xFFD6D7DC),
    inversePrimary = Color(0xFFDADBDF), inverseSurface = Color(0xFF303136), inverseOnSurface = Color(0xFFF5F5F7),
    surfaceTint = Color(0xFF777980),
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
