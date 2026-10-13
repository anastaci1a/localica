package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.*
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import kotlin.math.exp

/** Shared drawing/motion, retaining Slider's keyboard, touch and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InterfaceSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    val interaction = remember { MutableInteractionSource() }
    var pressure by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(value)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val deformation = rememberGlassBody({ (value - valueRange.start) / span }, { pressure }, { if (pressed || dragged) 1f else 0f })
    val trackSource = rememberFrostedBackdrop(Unit, enabled = false, dark = false, glassBlur = 0f)
    val inset = with(LocalDensity.current) { 20.dp.toPx() }
    val touch = Modifier.pointerInput(valueRange) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = (current - valueRange.start) / span
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        val raw = start + (it.position.x - down.position.x) / (size.width - 2 * inset).coerceAtLeast(1f)
                        pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
                    }
                } while (event.changes.any { it.pressed })
            } finally { pressure = 0f }
        }
    }
    val color = NavigationAccent
    Slider(value, onValueChange, modifier.then(touch), valueRange = valueRange, steps = steps, interactionSource = interaction,
        thumb = { CompositionLocalProvider(LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, contrast = 0f)) {
            LiquidGlassSurface(Modifier.size(40.dp, 26.dp).testTag("settings-slider-thumb").graphicsLayer {
                scaleX = deformation.value.scaleX(); scaleY = deformation.value.scaleY()
                val fraction = (value - valueRange.start) / span
                translationX = glassWallTranslation(size.width, scaleX, when { fraction <= .001f -> -1f; fraction >= .999f -> 1f; else -> 0f })
            }, source = trackSource, cornerRadius = 13.dp, pressFeedback = false, body = { deformation.value })
        } },
        track = {
            Canvas(Modifier.fillMaxWidth().height(48.dp).recordFrostedBackdrop(trackSource)) {
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
internal fun InterfaceSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    val speed = LocalMotionSpeed.current
    val reduced = LocalReducedMotion.current
    val position = remember { androidx.compose.animation.core.Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, reduced) {
        if (reduced) position.snapTo(if (checked) 1f else 0f)
        else position.animateTo(if (checked) 1f else 0f, spring(1f, 500f * speed * speed))
    }
    var raw by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    var pressure by remember { mutableFloatStateOf(0f) }
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val trackSource = rememberFrostedBackdrop(Unit, enabled = false, dark = false, glassBlur = 0f)
    val body = rememberGlassBody({ position.value }, { pressure }, { if (dragged) 1f else 0f })
    val active by animateFloatAsState(if (dragged || position.isRunning) 1f else 0f,
        tween(if (reduced) 0 else 100), label = "Switch lens")
    Box(Modifier.size(60.dp, 32.dp).testTag("settings-switch").then(if (onChange == null) Modifier else Modifier.draggable(
        rememberDraggableState { dx -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            raw += dx / with(density) { 24.dp.toPx() }
            pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
            position.snapTo(raw.coerceIn(0f, 1f))
        } }, Orientation.Horizontal, interactionSource = interaction,
        onDragStarted = { position.stop(); raw = position.value }, onDragStopped = {
            pressure = 0f
            val next = position.value >= .5f
            onChange(next)
            if (reduced) position.snapTo(if (next) 1f else 0f)
            else position.animateTo(if (next) 1f else 0f, spring(1f, 500f * speed * speed))
        }))) {
        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(trackSource)) {
            drawRoundRect(androidx.compose.ui.graphics.lerp(neutral, NavigationAccent, position.value.coerceIn(0f, 1f)),
                cornerRadius = CornerRadius(size.height / 2))
        }
        CompositionLocalProvider(LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, contrast = 0f)) {
            LiquidGlassSurface(Modifier.offset { androidx.compose.ui.unit.IntOffset((2.dp + 24.dp * position.value).roundToPx(), 2.dp.roundToPx()) }.size(32.dp, 28.dp).graphicsLayer {
                scaleX = body.value.scaleX(); scaleY = body.value.scaleY()
                translationX = glassWallTranslation(size.width, scaleX, when { position.value <= .001f -> -1f; position.value >= .999f -> 1f; else -> 0f })
            }, source = trackSource, cornerRadius = 14.dp, pressFeedback = false, body = { body.value }) {
                Canvas(Modifier.matchParentSize()) { drawRoundRect(Color.White.copy(alpha = 1f - active), cornerRadius = CornerRadius(size.height / 2)) }
            }
        }
    }
}

@Composable
internal fun InterfaceCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val progress by animateFloatAsState(if (checked) 1f else 0f,
        tween(if (LocalReducedMotion.current) 0 else (160 / LocalMotionSpeed.current).toInt()), label = "Checkmark")
    val foreground = LocalContentColor.current
    Box(modifier.size(48.dp).then(if (onCheckedChange != null)
        Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier)
        .graphicsLayer { alpha = if (enabled) 1f else .38f }, contentAlignment = Alignment.Center) {
        SelectionPane(Modifier.size(26.dp))
        Canvas(Modifier.fillMaxSize()) {
        val radius = 10.dp.toPx()
        drawCircle(foreground.copy(alpha = .45f), radius, style = Stroke(1.5.dp.toPx()))

        val d = 1.dp.toPx()
        val check = Path().apply { moveTo(center.x - 4.5f * d, center.y); lineTo(center.x - 1f * d, center.y + 3.5f * d); lineTo(center.x + 5f * d, center.y - 4f * d) }
        drawPath(check, foreground.copy(alpha = progress), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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
