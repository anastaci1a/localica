package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

internal enum class TransportGlyph { Previous, PlayPause, Next }

/** Original vector geometry: two halves of a play wedge resolve continuously into pause bars. */
@Composable
internal fun TransportButton(glyph: TransportGlyph, playing: Boolean, label: String, onClick: () -> Unit,
    modifier: Modifier, iconSize: Dp, tint: Color, enabled: Boolean, reducedMotion: Boolean) {
    val pause by animateFloatAsState(if (playing) 1f else 0f,
        tween(if (reducedMotion) 0 else 260, easing = FastOutSlowInEasing), label = "Play pause morph")
    val travel = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(modifier.ownButtonDrag(enabled).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null) {
            onClick()
            if (glyph != TransportGlyph.PlayPause && !reducedMotion) { animation?.cancel(); animation = scope.launch {
                travel.snapTo(0f)
                travel.animateTo(1f, tween(550, easing = LinearEasing))
                travel.snapTo(0f)
            } }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(iconSize)) {
            val color = tint.copy(alpha = tint.alpha * if (enabled) 1f else .38f)
            fun roundedPolygon(points: List<Offset>, alpha: Float = 1f) {
                val vertices = points.fold(mutableListOf<Offset>()) { result, p ->
                    if (result.lastOrNull() != p) result += p
                    result
                }
                val path = Path()
                val radius = .045f
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
                drawPath(path, color.copy(alpha = color.alpha * alpha))
            }
            fun polygon(from: List<Offset>, to: List<Offset>, progress: Float) =
                roundedPolygon(from.indices.map { from[it] + (to[it] - from[it]) * progress })
            if (glyph == TransportGlyph.PlayPause) {
                if (pause == 0f) {
                    // One contour avoids a shared antialiased edge in the settled triangle.
                    roundedPolygon(listOf(Offset(.2f, .1f), Offset(.8f, .5f), Offset(.2f, .9f)))
                } else {
                polygon(listOf(Offset(.2f,.1f), Offset(.5f,.3f), Offset(.5f,.7f), Offset(.2f,.9f)),
                    listOf(Offset(.2f,.1f), Offset(.4f,.1f), Offset(.4f,.9f), Offset(.2f,.9f)), pause)
                polygon(listOf(Offset(.5f,.3f), Offset(.8f,.5f), Offset(.8f,.5f), Offset(.5f,.7f)),
                    listOf(Offset(.6f,.1f), Offset(.8f,.1f), Offset(.8f,.9f), Offset(.6f,.9f)), pause)
                }
            } else scale(if (glyph == TransportGlyph.Previous) -1f else 1f, 1f) {
                // Rounded Heroicons wedges; directional keyframes adapted from lucide-animated.
                // Keep both wedges opaque and stagger their nudge instead of replacing/fading them.
                for (i in 0..1) {
                    val phase = ((travel.value - i * .12f) / .88f).coerceIn(0f, 1f)
                    val wave = if (phase < .4f) FastOutSlowInEasing.transform(phase / .4f)
                        else 1f - FastOutSlowInEasing.transform((phase - .4f) / .6f)
                    val x = .08f + i * .40f + .08f * wave
                    val path = Path().apply {
                        moveTo((x + .045f) * size.width, .21f * size.height)
                        quadraticTo(x * size.width, .18f * size.height, x * size.width, .25f * size.height)
                        lineTo(x * size.width, .75f * size.height)
                        quadraticTo(x * size.width, .82f * size.height, (x + .045f) * size.width, .79f * size.height)
                        lineTo((x + .39f) * size.width, .54f * size.height)
                        quadraticTo((x + .44f) * size.width, .5f * size.height, (x + .39f) * size.width, .46f * size.height)
                        close()
                    }
                    drawPath(path, color)
                }
            }
        }
    }
}
