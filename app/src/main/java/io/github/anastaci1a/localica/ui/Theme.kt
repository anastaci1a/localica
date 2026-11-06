package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.normalizedGrayTone

internal val NavigationAccent = Color(0xFFEB315A)
private val InterfaceFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold))
private val InterfaceTypography = Typography().let { base ->
    fun androidx.compose.ui.text.TextStyle.face() = copy(fontFamily = InterfaceFont, letterSpacing = 0.sp)
    base.copy(
        displayLarge = base.displayLarge.face(), displayMedium = base.displayMedium.face(), displaySmall = base.displaySmall.face(),
        headlineLarge = base.headlineLarge.face(), headlineMedium = base.headlineMedium.face(), headlineSmall = base.headlineSmall.face(),
        titleLarge = base.titleLarge.face().copy(fontWeight = FontWeight.Bold), titleMedium = base.titleMedium.face(), titleSmall = base.titleSmall.face(),
        bodyLarge = base.bodyLarge.face(), bodyMedium = base.bodyMedium.face(), bodySmall = base.bodySmall.face(),
        labelLarge = base.labelLarge.face(), labelMedium = base.labelMedium.face(), labelSmall = base.labelSmall.face())
}

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

private val GlassDarkColors = androidx.compose.material3.darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF202124),
    primaryContainer = Color(0xFF414249), onPrimaryContainer = Color.White,
    secondary = Color(0xFFE4E4E8), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF414249), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFE4E4E8), onTertiary = Color(0xFF202124),
    tertiaryContainer = Color(0xFF414249), onTertiaryContainer = Color.White,
    background = Color(0xFF242528), onBackground = Color.White,
    surface = Color(0xFF242528), onSurface = Color.White, onSurfaceVariant = Color(0xFFE4E4E8),
    surfaceContainer = Color(0xFF303136), surfaceContainerLow = Color(0xFF242528),
    surfaceContainerHigh = Color(0xFF414249), surfaceContainerHighest = Color(0xFF4A4B50),
    outline = Color(0xFFA6A7AD), outlineVariant = Color(0xFF61636A), surfaceTint = Color.White,
)

private fun lerpScheme(a: androidx.compose.material3.ColorScheme, b: androidx.compose.material3.ColorScheme, f: Float): androidx.compose.material3.ColorScheme {
    fun mix(x: Color, y: Color) = androidx.compose.ui.graphics.lerp(x, y, f)
    return a.copy(
        primary = mix(a.primary, b.primary), onPrimary = mix(a.onPrimary, b.onPrimary),
        primaryContainer = mix(a.primaryContainer, b.primaryContainer), onPrimaryContainer = mix(a.onPrimaryContainer, b.onPrimaryContainer),
        secondary = mix(a.secondary, b.secondary), onSecondary = mix(a.onSecondary, b.onSecondary),
        secondaryContainer = mix(a.secondaryContainer, b.secondaryContainer), onSecondaryContainer = mix(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = mix(a.tertiary, b.tertiary), onTertiary = mix(a.onTertiary, b.onTertiary),
        tertiaryContainer = mix(a.tertiaryContainer, b.tertiaryContainer), onTertiaryContainer = mix(a.onTertiaryContainer, b.onTertiaryContainer),
        background = mix(a.background, b.background), onBackground = mix(a.onBackground, b.onBackground),
        surface = mix(a.surface, b.surface), onSurface = mix(a.onSurface, b.onSurface), onSurfaceVariant = mix(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceContainer = mix(a.surfaceContainer, b.surfaceContainer), surfaceContainerLow = mix(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainerHigh = mix(a.surfaceContainerHigh, b.surfaceContainerHigh), surfaceContainerHighest = mix(a.surfaceContainerHighest, b.surfaceContainerHighest),
        outline = mix(a.outline, b.outline), outlineVariant = mix(a.outlineVariant, b.outlineVariant), surfaceTint = mix(a.surfaceTint, b.surfaceTint))
}

/** Popup controls and explicit secondary labels must agree with the material's ink. */
@Composable
internal fun GlassPanelTheme(dark: Boolean, content: @Composable () -> Unit) = GlassPanelTheme(if (dark) 1f else 0f, content)

/** [lightMix] is the light-ink fraction (0 dark ink, 1 light ink); a fraction between them is the same scheme
 * interpolated, so a polarity change can ease with an animation instead of switching in one frame. */
@Composable
internal fun GlassPanelTheme(lightMix: Float, content: @Composable () -> Unit) {
    val ambient = MaterialTheme.colorScheme
    val light = ambient.takeIf { it.onSurface.red < .5f } ?: LightColors
    val colors = when {
        lightMix >= 1f -> GlassDarkColors
        lightMix <= 0f -> light
        else -> lerpScheme(light, GlassDarkColors, lightMix)
    }
    MaterialTheme(colorScheme = colors) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides colors.onSurface,
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(color = Color.Unspecified),
            content = content)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalicaTheme(grayTone: Float = .4f, content: @Composable () -> Unit) {
    // Nested artwork/settings themes reuse the same owner. Retained dialog caller
    // contexts must keep this live reference even after their declaration is gone.
    val inheritedRenderer = LocalGlassRendererRuntime.current
    val renderer = inheritedRenderer ?: remember {
        GlassRendererRuntime(io.github.anastaci1a.localica.navigation.GlassRendererMode.Normal)
    }
    val level = .18f + .36f * grayTone.normalizedGrayTone()
    val gray = Color(level, level, level + .012f)
    MaterialTheme(colorScheme = LightColors.copy(onSurfaceVariant = gray, secondary = gray, tertiary = gray,
        outline = Color(level + .13f, level + .13f, level + .142f)), typography = InterfaceTypography,
        shapes = androidx.compose.material3.Shapes(small = Corners.card, medium = Corners.menu, large = Corners.largeArtwork, extraLarge = Corners.panel),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalGlassRendererRuntime provides renderer,
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            androidx.compose.material3.LocalRippleConfiguration provides null,
        ) { ModalGestureRoot { TextEditingFocusHost { GlassDialogHost(content) } } }
    }
}
