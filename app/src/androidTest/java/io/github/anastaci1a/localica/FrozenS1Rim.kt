// Independent d893d6dc4 baseline: names changed; supplied matched geometric inputs replace GlassShape.
package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.*

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp

/** One draw owner's current preparation; paths are replaced, never rewritten after publication. */
internal class FrozenS1RimCache {
    private var center: Offset? = null
    private var tone: GlassTone? = null
    private var brushes: FrozenS1RimBrushes? = null
    private var bounds: Rect? = null
    private var radius = Float.NaN
    private var path: Path? = null

    fun brushes(center: Offset, tone: GlassTone): FrozenS1RimBrushes {
        if (this.center == center && this.tone == tone) brushes?.let { return it }
        return frozenS1RimBrushes(center, tone).also {
            this.center = center
            this.tone = tone
            brushes = it
        }
    }

    fun roundedPath(bounds: Rect, radius: Float): Path {
        if (this.bounds == bounds && this.radius == radius) path?.let { return it }
        return Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }.also {
            this.bounds = bounds
            this.radius = radius
            path = it
        }
    }
}

internal data class FrozenS1RimBrushes(val shine: Brush, val shade: Brush)

internal fun frozenS1RimBrushes(center: Offset, tone: GlassTone): FrozenS1RimBrushes {
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
    return FrozenS1RimBrushes(shine, shade)
}
