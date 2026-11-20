package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import io.github.anastaci1a.localica.ui.spec.FlexGlow

/** Retained preparation for the shared rim only. */
internal class GlassRimCache {
    private var center: Offset? = null
    private var tone: GlassTone? = null
    private var brushes: GlassRimBrushes? = null
    fun brushes(center: Offset, tone: GlassTone): GlassRimBrushes {
        if (this.center == center && this.tone == tone) brushes?.let { return it }
        return glassRimBrushes(center, tone).also {
            this.center = center
            this.tone = tone
            brushes = it
        }
    }

}

internal data class GlassRimBrushes(val shine: Brush, val shade: Brush)

internal fun glassRimBrushes(center: Offset, tone: GlassTone): GlassRimBrushes {
    val white = tone.color(Color.White)
    val black = tone.color(Color.Black)
    // Diagonally opposed highlights taper into quiet, slightly shaded corners.
    // The silhouette clips both strokes inward; neither changes the hit geometry.
    val shine = Brush.sweepGradient(
        0f to white.copy(alpha = .025f), .125f to white.copy(alpha = .32f),
        .25f to white.copy(alpha = .025f), .375f to Color.Transparent,
        .5f to white.copy(alpha = .025f), .625f to white.copy(alpha = .32f),
        .75f to white.copy(alpha = .025f), .875f to Color.Transparent,
        1f to white.copy(alpha = .025f), center = center)
    val shade = Brush.sweepGradient(
        0f to Color.Transparent, .125f to Color.Transparent, .25f to Color.Transparent,
        .375f to black.copy(alpha = .11f), .5f to Color.Transparent,
        .625f to Color.Transparent, .75f to Color.Transparent, .875f to black.copy(alpha = .11f),
        1f to Color.Transparent, center = center)
    return GlassRimBrushes(shine, shade)
}
