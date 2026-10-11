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
    Box(modifier.semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null) {
            onClick()
            if (glyph != TransportGlyph.PlayPause && !reducedMotion) scope.launch {
                travel.snapTo(0f)
                travel.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
                travel.snapTo(0f)
            }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(iconSize)) {
            val color = tint.copy(alpha = tint.alpha * if (enabled) 1f else .38f)
            fun polygon(from: List<Offset>, to: List<Offset>, progress: Float, alpha: Float = 1f) {
                val path = Path()
                from.indices.forEach { i ->
                    val point = from[i] + (to[i] - from[i]) * progress
                    if (i == 0) path.moveTo(point.x * size.width, point.y * size.height)
                    else path.lineTo(point.x * size.width, point.y * size.height)
                }
                path.close(); drawPath(path, color.copy(alpha = color.alpha * alpha))
            }
            if (glyph == TransportGlyph.PlayPause) {
                polygon(listOf(Offset(.2f,.1f), Offset(.5f,.3f), Offset(.5f,.7f), Offset(.2f,.9f)),
                    listOf(Offset(.2f,.1f), Offset(.4f,.1f), Offset(.4f,.9f), Offset(.2f,.9f)), pause)
                polygon(listOf(Offset(.5f,.3f), Offset(.8f,.5f), Offset(.8f,.5f), Offset(.5f,.7f)),
                    listOf(Offset(.6f,.1f), Offset(.8f,.1f), Offset(.8f,.9f), Offset(.6f,.9f)), pause)
            } else scale(if (glyph == TransportGlyph.Previous) -1f else 1f, 1f) {
                val t = travel.value
                for (i in -1..1) {
                    val x = .05f + (i + t) * .36f
                    val opacity = when (i) { -1 -> t; 1 -> 1f - t; else -> 1f }
                    val points = listOf(Offset(x,.18f), Offset(x+.36f,.5f), Offset(x,.82f))
                    polygon(points, points, 0f, opacity)
                }
                drawRoundRect(color, Offset(size.width * .82f, size.height * .18f),
                    Size(size.width * .08f, size.height * .64f), androidx.compose.ui.geometry.CornerRadius(size.width * .025f))
            }
        }
    }
}
