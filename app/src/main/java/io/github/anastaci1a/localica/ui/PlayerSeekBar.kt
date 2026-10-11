package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Keep Slider's touch, keyboard and accessibility behavior; draw a quiet, continuous track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val thickness by animateDpAsState(if (dragging) 9.dp else 5.dp, tween(160), label = "Seek thickness")
    Slider(value, onValueChange, modifier, enabled, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = { Box(Modifier.width(0.dp).height(40.dp)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val h = thickness.toPx()
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val top = (size.height - h) / 2
                drawRoundRect(Color.White.copy(alpha = .2f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                if (fraction > 0f) drawRoundRect(Color.White.copy(alpha = .7f), Offset(0f, top), Size(size.width * fraction, h), CornerRadius(h / 2))
                if (dragging) drawCircle(Color.White.copy(alpha = .9f), h * .7f, Offset(size.width * fraction, size.height / 2))
            }
        })
}
