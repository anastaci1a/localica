package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.browse.BrowseStyle

internal data class GalleryLoadingTextMetrics(
    val key: GalleryLoadingTextKey,
    val titleLine: Int, val informationLine: Int,
    val titleReservation: Int, val informationReservation: Int,
)

internal data class GalleryLoadingTextKey(val title: TextStyle, val information: TextStyle,
    val density: Density, val direction: LayoutDirection, val resolver: FontFamily.Resolver)

internal val LocalGalleryLoadingTextMetrics = staticCompositionLocalOf<GalleryLoadingTextMetrics?> { null }

/** Text resolves nonlinear font scaling and font metrics before rounding its line box.
 * Directly converting lineHeight.sp underestimates that box at large font sizes. Keep
 * the one-line visual shapes separate from the complete two-line row reservation. */
@Composable
internal fun rememberGalleryLoadingTextMetrics(style: BrowseStyle, columns: Int): GalleryLoadingTextMetrics {
    val title = MaterialTheme.typography.titleMedium.copy(
        fontSize = style.browseTitleSize(columns).sp, lineHeight = style.browseTitleLine(columns).sp)
    val information = MaterialTheme.typography.bodySmall
    val key = GalleryLoadingTextKey(title, information, LocalDensity.current,
        LocalLayoutDirection.current, LocalFontFamilyResolver.current)
    // The viewport shares this result across loading and ready cells. A nested
    // typography/density/resolver override must still obtain its own measurement.
    LocalGalleryLoadingTextMetrics.current?.takeIf { it.key == key }?.let { return it }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    return remember(measurer, key) {
        fun height(text: String, textStyle: TextStyle, lines: Int) =
            measurer.measure(text, textStyle, maxLines = lines).size.height
        GalleryLoadingTextMetrics(key, height("I", title, 1), height("I", information, 1),
            height("I\nI", title, 2), height("I\nI", information, 2))
    }
}
