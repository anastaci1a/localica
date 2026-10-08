package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFAF99), onPrimary = Color(0xFF5B1808),
    primaryContainer = Color(0xFF7E2B17), onPrimaryContainer = Color(0xFFFFDAD0),
    secondaryContainer = Color(0xFF503A32), onSecondaryContainer = Color(0xFFFFDAD0),
    background = Color(0xFF191B18), surface = Color(0xFF191B18),
    surfaceContainer = Color(0xFF252823), surfaceVariant = Color(0xFF353A32),
    onSurface = Color(0xFFE6E8E0), onSurfaceVariant = Color(0xFFBFC4B7),
    outlineVariant = Color(0xFF41473D),
)

@Composable
fun LocalicaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
