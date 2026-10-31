package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.GlassTone
import io.github.anastaci1a.localica.ui.GlassRimPreparation

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOriginalGlassRim(
    bounds: androidx.compose.ui.geometry.Rect, radius: Float, path: Path? = null, tone: GlassTone = GlassTone(), opacity: Float = 1f, observe: ((GlassRimPreparation) -> Unit)? = null) {
    val white = tone.color(Color.White)
    val black = tone.color(Color.Black)
    // Diagonally opposed highlights taper into quiet, slightly shaded corners.
    // The silhouette clips both strokes inward; neither changes the hit geometry.
    val shine = Brush.sweepGradient(
        0f to white.copy(alpha = .025f), .125f to white.copy(alpha = .32f),
        .25f to white.copy(alpha = .025f), .375f to Color.Transparent,
        .5f to white.copy(alpha = .025f), .625f to white.copy(alpha = .32f),
        .75f to white.copy(alpha = .025f), .875f to Color.Transparent,
        1f to white.copy(alpha = .025f), center = bounds.center)
    val shade = Brush.sweepGradient(
        0f to Color.Transparent, .125f to Color.Transparent, .25f to Color.Transparent,
        .375f to black.copy(alpha = .11f), .5f to Color.Transparent,
        .625f to Color.Transparent, .75f to Color.Transparent, .875f to black.copy(alpha = .11f),
        1f to Color.Transparent, center = bounds.center)
    observe?.invoke(GlassRimPreparation.BrushPair)
    val silhouette = path ?: Path().apply {
        addRoundRect(RoundRect(bounds, CornerRadius(radius)))
        observe?.invoke(GlassRimPreparation.RoundedPath)
    }
    clipPath(silhouette) {
        drawPath(silhouette, shine, alpha = opacity, style = Stroke(2.6.dp.toPx()))
        drawPath(silhouette, shade, alpha = opacity, style = Stroke(1.4.dp.toPx()))
    }
}
