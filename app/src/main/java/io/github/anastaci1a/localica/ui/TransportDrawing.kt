package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.floor
import kotlin.math.roundToInt

internal val SkipEasing = CubicBezierEasing(.42f, 0f, .12f, 1f)
internal fun skipDuration(speed: Float) = (380f * (4f / 3f) / speed).roundToInt()

/** Keep the incoming velocity when a press extends a moving cycle. */
internal fun skipRetargetEasing(velocity: Float, distance: Float, duration: Int) =
    CubicBezierEasing(.42f, (velocity * duration / 1000f / distance * .42f).coerceIn(0f, 1f), .12f, 1f)

internal data class SkipElement(val x: Float, val alpha: Float)
internal fun skipElements(travel: Float): List<SkipElement> {
    val phase = travel - floor(travel)
    return listOf(SkipElement(.08f + (phase - 1f) * .4f, phase),
        SkipElement(.08f + phase * .4f, 1f),
        SkipElement(.08f + (phase + 1f) * .4f, 1f - phase))
}

/** The rest wedges span [SkipRestInset, 1 - SkipRestInset] of the canvas. */
internal const val SkipRestInset = .08f
/** Each fade band starts this far outside the rest bounds, so the rest icon is never dimmed. */
internal const val SkipTaperMargin = .02f
/** The band then ramps linearly to transparent at the canvas edge. */
internal const val SkipTaperBand = SkipRestInset - SkipTaperMargin

/** Horizontal opacity mask for travelling skip wedges, symmetric about the canvas centre. */
internal fun skipTaperAlpha(x: Float): Float = (minOf(x, 1f - x) / SkipTaperBand).coerceIn(0f, 1f)

/** Fade content through the edge bands instead of clipping it. A settled cycle draws only rest
 * wedges, where the mask is opaque, so it skips the layer and stays the static icon exactly.
 */
private inline fun DrawScope.drawWithSkipTaper(phase: Float, content: DrawScope.() -> Unit) {
    if (phase == 0f) return content()
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    content()
    drawRect(Brush.horizontalGradient(0f to Color.Transparent, SkipTaperBand to Color.Black,
        1f - SkipTaperBand to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    canvas.restore()
}

/** Normalized, corresponding contours retain the existing two-piece play/pause morph.
 * A pack can provide new endpoints without replacing its interpolation or timing.
 */
internal data class PlayPauseShapes(
    val playOutline: List<Offset> = listOf(Offset(.2f,.1f), Offset(.8f,.5f), Offset(.2f,.9f)),
    val playLeft: List<Offset> = listOf(Offset(.2f,.1f), Offset(.5f,.3f), Offset(.5f,.7f), Offset(.2f,.9f)),
    val playRight: List<Offset> = listOf(Offset(.5f,.3f), Offset(.8f,.5f), Offset(.8f,.5f), Offset(.5f,.7f)),
    val pauseLeft: List<Offset> = listOf(Offset(.2f,.1f), Offset(.4f,.1f), Offset(.4f,.9f), Offset(.2f,.9f)),
    val pauseRight: List<Offset> = listOf(Offset(.6f,.1f), Offset(.8f,.1f), Offset(.8f,.9f), Offset(.6f,.9f)),
    val cornerRadius: Float = .045f,
) {
    init {
        require(playOutline.size >= 3)
        require(playLeft.size >= 3 && playLeft.size == pauseLeft.size)
        require(playRight.size >= 3 && playRight.size == pauseRight.size)
        require(cornerRadius >= 0f && cornerRadius.isFinite())
        require(listOf(playOutline, playLeft, playRight, pauseLeft, pauseRight).flatten()
            .all { it.x.isFinite() && it.y.isFinite() })
    }
}

/** The element callback draws one right-facing wedge in local canvas coordinates.
 * Its width is .44 of the canvas, with .40 pitch; Previous mirrors the same element.
 * This seam also accepts a pack's vector or tinted image renderer later.
 */
internal data class TransportShapes(
    val playPause: PlayPauseShapes = PlayPauseShapes(),
    val skipTriangle: DrawScope.(Color) -> Unit = { color ->
        drawPath(Path().apply {
            moveTo(.045f * size.width, .21f * size.height)
            quadraticTo(0f, .18f * size.height, 0f, .25f * size.height)
            lineTo(0f, .75f * size.height)
            quadraticTo(0f, .82f * size.height, .045f * size.width, .79f * size.height)
            lineTo(.39f * size.width, .54f * size.height)
            quadraticTo(.44f * size.width, .5f * size.height, .39f * size.width, .46f * size.height)
            close()
        }, color)
    },
)
internal val LocalTransportShapes = staticCompositionLocalOf { TransportShapes() }

/** Arbitrary imported contours share transport timing and interruption state.
 * Play/pause endpoints fade in fixed bounds when no [PackPlayPauseMorph] applies; imported skip glyphs
 * travel through a clipped cycle, since their rest bounds are the whole canvas and leave no room outside
 * them for a taper.
 */
internal fun DrawScope.drawTransportPainters(glyph: TransportGlyph, pause: Float, travel: Float,
    color: Color, painter: Painter, pausePainter: Painter) {
    fun draw(p: Painter, alpha: Float = 1f) {
        if (alpha > 0f) with(p) { draw(size, alpha = alpha, colorFilter = ColorFilter.tint(color)) }
    }
    if (glyph == TransportGlyph.PlayPause) {
        draw(painter, 1f - pause)
        draw(pausePainter, pause)
    } else clipRect {
        val phase = travel - floor(travel)
        val direction = if (glyph == TransportGlyph.Previous) -1f else 1f
        for (copy in -1..1) translate(left = (copy + phase) * size.width * direction) { draw(painter) }
    }
}

internal fun DrawScope.drawTransportGlyph(glyph: TransportGlyph, pause: Float, travel: Float,
    color: Color, shapes: TransportShapes) {
    if (glyph == TransportGlyph.PlayPause) {
        val p = shapes.playPause
        if (pause == 0f) roundedTransportPolygon(p.playOutline, p.cornerRadius, color)
        else {
            fun morph(from: List<Offset>, to: List<Offset>) =
                roundedTransportPolygon(from.indices.map { from[it] + (to[it] - from[it]) * pause }, p.cornerRadius, color)
            morph(p.playLeft, p.pauseLeft)
            morph(p.playRight, p.pauseRight)
        }
    } else drawWithSkipTaper(travel - floor(travel)) {
        scale(if (glyph == TransportGlyph.Previous) -1f else 1f, 1f) {
            for (element in skipElements(travel)) if (element.alpha > 0f) {
                translate(left = element.x * size.width) {
                    shapes.skipTriangle(this, color.copy(alpha = color.alpha * element.alpha))
                }
            }
        }
    }
}

private fun DrawScope.roundedTransportPolygon(points: List<Offset>, radius: Float, color: Color) {
    val vertices = points.fold(mutableListOf<Offset>()) { result, point ->
        if (result.lastOrNull() != point) result += point
        result
    }
    val path = Path()
    vertices.forEachIndexed { i, point ->
        val before = vertices[(i + vertices.size - 1) % vertices.size] - point
        val after = vertices[(i + 1) % vertices.size] - point
        val a = point + before * (radius / before.getDistance().coerceAtLeast(.001f)).coerceAtMost(.45f)
        val b = point + after * (radius / after.getDistance().coerceAtLeast(.001f)).coerceAtMost(.45f)
        if (i == 0) path.moveTo(a.x * size.width, a.y * size.height)
        else path.lineTo(a.x * size.width, a.y * size.height)
        path.quadraticTo(point.x * size.width, point.y * size.height, b.x * size.width, b.y * size.height)
    }
    path.close()
    drawPath(path, color)
}
