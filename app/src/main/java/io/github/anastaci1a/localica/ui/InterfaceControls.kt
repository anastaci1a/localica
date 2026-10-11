package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared drawing/motion, retaining Slider's keyboard, touch and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InterfaceSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    val interaction = remember { MutableInteractionSource() }
    val speed = LocalMotionSpeed.current
    val dragged by interaction.collectIsDraggedAsState()
    val expansion by animateFloatAsState(if (dragged) 1.12f else 1f,
        if (LocalReducedMotion.current) tween(0) else spring(.75f, 700f * speed * speed), label = "Slider pressure")
    val color = MaterialTheme.colorScheme.onSurface
    Slider(value, onValueChange, modifier, valueRange = valueRange, steps = steps, interactionSource = interaction,
        thumb = { LiquidGlassSurface(Modifier.size(24.dp).graphicsLayer { scaleX = expansion; scaleY = expansion },
            source = null, tint = Color.White.copy(alpha = .94f), pressFeedback = false) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val h = 5.dp.toPx()
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val top = (size.height - h) / 2
                drawRoundRect(color.copy(alpha = .16f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                if (fraction > 0f) drawRoundRect(color.copy(alpha = .8f), Offset(0f, top), Size(size.width * fraction, h), CornerRadius(h / 2))
                if (steps > 0) for (index in 0..steps + 1) drawCircle(color.copy(alpha = .4f), 2.dp.toPx(),
                    Offset(size.width * index / (steps + 1), size.height / 2))
            }
        })
}

/** The containing preference row owns the toggle's semantics and full-width tap target. */
@Composable
internal fun InterfaceSwitch(checked: Boolean) {
    val speed = LocalMotionSpeed.current
    val position by animateFloatAsState(if (checked) 1f else 0f,
        if (LocalReducedMotion.current) tween(0) else spring(.72f, 600f * speed * speed), label = "Switch position")
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f)
    Box(Modifier.size(52.dp, 32.dp)) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(androidx.compose.ui.graphics.lerp(neutral, NavigationAccent, position.coerceIn(0f, 1f)),
                cornerRadius = CornerRadius(size.height / 2))
        }
        LiquidGlassSurface(Modifier.offset(x = 2.dp + 20.dp * position, y = 2.dp).size(28.dp), source = null,
            tint = Color.White.copy(alpha = .96f), pressFeedback = false)
    }
}

@Composable
internal fun InterfaceCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val progress by animateFloatAsState(if (checked) 1f else 0f,
        tween(if (LocalReducedMotion.current) 0 else (160 / LocalMotionSpeed.current).toInt()), label = "Checkmark")
    val foreground = LocalContentColor.current
    val ink = if (foreground.luminance() > .5f) Color.Black else Color.White
    Canvas(modifier.size(48.dp).then(if (onCheckedChange != null)
        Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier)
        .graphicsLayer { alpha = if (enabled) 1f else .38f }) {
        val radius = 10.dp.toPx()
        drawCircle(foreground.copy(alpha = .45f), radius, style = Stroke(1.5.dp.toPx()))
        drawCircle(foreground.copy(alpha = progress), radius)
        val d = 1.dp.toPx()
        val check = Path().apply { moveTo(center.x - 4.5f * d, center.y); lineTo(center.x - 1f * d, center.y + 3.5f * d); lineTo(center.x + 5f * d, center.y - 4f * d) }
        drawPath(check, ink.copy(alpha = progress), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun GlassFilterChoice(checked: Boolean, onClick: () -> Unit, label: String, enabled: Boolean) {
    Box(Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)) {
        LiquidGlassSurface(Modifier.height(40.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { selected = checked }.graphicsLayer { alpha = if (enabled) 1f else .38f },
            tint = Color.Black.copy(alpha = if (checked) .09f else 0f), pressFeedback = enabled) {
            Text(label, Modifier.align(Alignment.Center).padding(horizontal = 14.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}
