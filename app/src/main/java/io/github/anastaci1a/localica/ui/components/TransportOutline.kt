package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import io.github.anastaci1a.localica.ui.engine.TransportOutline as OutlineOwner
import io.github.anastaci1a.localica.ui.engine.drawTransportTaper as drawOwnedTaper

/** Static pack painters own endpoints; the retained outline owns every intermediate frame. */
internal class TransportOutline(private val owner: OutlineOwner) {
    fun DrawScope.draw(progress: Float, color: Color, play: Painter, pause: Painter) {
        when {
            progress <= 0f -> with(play) { draw(size, colorFilter = ColorFilter.tint(color)) }
            progress >= 1f -> with(pause) { draw(size, colorFilter = ColorFilter.tint(color)) }
            else -> with(owner) { draw(progress, color) }
        }
    }
}

@Composable
internal fun rememberTransportOutline(pack: Any, leftFrom: List<Offset>, leftTo: List<Offset>,
    rightFrom: List<Offset>, rightTo: List<Offset>): TransportOutline = remember(pack) {
    TransportOutline(OutlineOwner(leftFrom, leftTo, rightFrom, rightTo))
}

internal inline fun DrawScope.drawTransportTaper(phase: Float, band: Float, content: DrawScope.() -> Unit) =
    drawOwnedTaper(phase, band, content)
