package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import io.github.anastaci1a.localica.ui.spec.TouchLightRimWidth

/** One draw owner's current preparation; paths are replaced, never rewritten after publication. */
internal class GlassRimCache {
    private var lightTone: GlassTone? = null
    private var lightShader: Shader? = null
    private var lightBrush: ShaderBrush? = null
    private val lightMatrix = android.graphics.Matrix()
    private var lightStroke = Stroke(0f)
    fun light(point: Offset, radius: Float, tone: GlassTone): Brush {
        if (lightTone != tone) {
            lightShader = android.graphics.RadialGradient(0f, 0f, 1f, tone.color(Color.White).toArgb(), Color.Transparent.toArgb(), android.graphics.Shader.TileMode.CLAMP)
            lightBrush = ShaderBrush(requireNotNull(lightShader)); lightTone = tone
        }
        lightMatrix.setScale(radius, radius); lightMatrix.postTranslate(point.x, point.y)
        lightShader!!.setLocalMatrix(lightMatrix)
        return lightBrush!!
    }
    fun lightStroke(width: Float): Stroke { if (lightStroke.width != width) lightStroke = Stroke(width); return lightStroke }
    private var center: Offset? = null
    private var tone: GlassTone? = null
    private var brushes: GlassRimBrushes? = null
    private var bounds: Rect? = null
    private var radius = Float.NaN
    private var path: Path? = null

    fun brushes(center: Offset, tone: GlassTone): GlassRimBrushes {
        if (this.center == center && this.tone == tone) brushes?.let { return it }
        return glassRimBrushes(center, tone).also {
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

internal fun glassContactPoint(point: Offset, size: Size, shape: GlassShape, vertical: Boolean): Offset =
    Offset(size.width * (.5f + (point.x - .5f) / shape.scaleX(vertical)), size.height * (.5f + (point.y - .5f) / shape.scaleY(vertical)))

internal inline fun DrawScope.drawContactLight(point: () -> Offset, path: () -> Path, alpha: Float, tone: GlassTone, cache: GlassRimCache,
    radius: Float = size.center.getDistance(), rimGain: Float = 2f) {
    if (alpha == 0f) return
    val silhouette = path()
    val brush = cache.light(point(), radius, tone)
    drawRect(brush, alpha = alpha)
    clipPath(silhouette) { drawPath(silhouette, brush, alpha = alpha * rimGain, style = cache.lightStroke(TouchLightRimWidth.toPx())) }
}
