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

internal data class FrozenS1RimBrushes(val shine: Brush, val shade: Brush, val light: Lazy<Brush>)

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
    return FrozenS1RimBrushes(shine, shade, lazy {
        Brush.radialGradient(*FrozenS1Glow.Falloff.map { (position, alpha) -> position to white.copy(alpha = alpha) }.toTypedArray(), center = Offset.Zero, radius = 1f)
    })
}

internal fun DrawScope.drawFrozenS1Glow(glow: GlassGlow, shape: () -> S1OpticalState, vertical: Boolean, tone: GlassTone, cache: FrozenS1RimCache) {
    if (glow.big > 0f) drawRect(tone.color(Color.White), alpha = glow.big * FrozenS1Glow.Gain)
    if (glow.little > 0f) {
        val shape = shape()
        val point = Offset(size.width * (.5f + (glow.point.x - .5f) / shape.scaleX(vertical)),
            size.height * (.5f + (glow.point.y - .5f) / shape.scaleY(vertical)))
        val radius = minOf(size.minDimension * FrozenS1Glow.ExtentScale, FrozenS1Glow.ExtentCap.dp.toPx())
        val brush = cache.brushes(size.center, tone).light.value
        translate(point.x, point.y) { scale(radius, radius, pivot = Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha = glow.little * FrozenS1Glow.Gain) } }
    }
}

internal object FrozenS1Glow {
    const val Show = .1f
    const val Hide = .5f
    const val Follow = .15f
    const val Gain = .12f
    const val ExtentScale = .75f
    const val ExtentCap = 80f
    val Falloff = listOf(0f to 1f, 1f to 0f)
}
