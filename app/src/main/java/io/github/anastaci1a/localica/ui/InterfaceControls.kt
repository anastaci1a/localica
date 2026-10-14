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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawWithCache
import kotlin.math.exp

/** Captions share the real slider-track inset and remain visually attached to it. */
@Composable internal fun SliderEndpoints(low: String, high: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-6).dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(low, style = MaterialTheme.typography.bodySmall)
        Text(high, style = MaterialTheme.typography.bodySmall)
    }
}

/** Shared drawing/motion, retaining Slider's keyboard, touch and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InterfaceSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true) {
    val actualSteps = if (steps == 0 && valueRange.start == 0f && valueRange.endInclusive == 1f) 19 else steps
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    val interaction = remember { MutableInteractionSource() }
    var pressure by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(value)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)
    var fingerFraction by remember { mutableStateOf<Float?>(null) }
    var contact by remember { mutableStateOf(false) }
    val shownFraction by animateFloatAsState(fingerFraction ?: ((value - valueRange.start) / span),
        if (fingerFraction != null || LocalReducedMotion.current) androidx.compose.animation.core.snap() else spring(1f, 650f), label = "Magnetic slider")
    val deformation = rememberGlassBody({ shownFraction }, { pressure }, { if (contact) 1f else 0f })
    val inset = with(LocalDensity.current) { 20.dp.toPx() }
    val touch = if (!enabled) Modifier else Modifier.pointerInput(valueRange, rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            contact = true
            val extent = (size.width - 2 * inset).coerceAtLeast(1f)
            val fraction = (current - valueRange.start) / span
            val pointer = if (rtl) size.width - down.position.x else down.position.x
            val thumb = inset + fraction * extent
            val start = if (kotlin.math.abs(pointer - thumb) <= inset) fraction else ((pointer - inset) / extent).coerceIn(0f, 1f)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        val raw = start + direction * (it.position.x - down.position.x) / extent
                        pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
                        fingerFraction = if (actualSteps > 0) magneticChoicePosition(raw.coerceIn(0f, 1f) * (actualSteps + 1)) / (actualSteps + 1)
                            else raw.coerceIn(0f, 1f)
                    }
                } while (event.changes.any { it.pressed })
            } finally { pressure = 0f; fingerFraction = null; contact = false }
        }
    }
    val color = NavigationAccent
    Slider(value, onValueChange, modifier.then(touch), enabled = enabled, valueRange = valueRange, steps = actualSteps, interactionSource = interaction,
        // Material owns semantics and input only. The complete visual lives in one
        // scene below, so the real and refracted tracks cannot be drawn twice.
        thumb = { Box(Modifier.size(40.dp, 26.dp).testTag("settings-slider-thumb")) },
        track = {
            GlassControlScene(Modifier.fillMaxWidth().height(48.dp).testTag("settings-slider-scene"),
                geometry = {
                    val fraction = shownFraction.coerceIn(0f, 1f)
                    val shape = deformation.value
                    val width = 40.dp.toPx() * shape.scaleX()
                    val height = 26.dp.toPx() * shape.scaleY()
                    val wall = direction * when { fraction <= .001f -> -1f; fraction >= .999f -> 1f; else -> 0f }
                    val x = size.width * (if (rtl) 1f - fraction else fraction) + glassWallTranslation(40.dp.toPx(), shape.scaleX(), wall)
                    LensGeometry(androidx.compose.ui.geometry.Rect(x - width / 2f, center.y - height / 2f,
                        x + width / 2f, center.y + height / 2f), height / 2f)
                }, body = { deformation.value }) {
                    drawSliderTrack(shownFraction.coerceIn(0f, 1f), actualSteps, enabled, rtl, color)
                }
        })
}

/** Stop markers live inside rounded caps, including the current active endpoint. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSliderTrack(fraction: Float, steps: Int,
    enabled: Boolean, rtl: Boolean, color: Color) {
    val h = 5.dp.toPx(); val radius = h / 2; val top = (size.height - h) / 2
    drawRoundRect(color.copy(alpha = if (enabled) .16f else .08f), Offset(0f, top), Size(size.width, h), CornerRadius(radius))
    val length = size.width * fraction
    if (fraction > 0f) drawRoundRect(color.copy(alpha = if (enabled) .8f else .3f),
        Offset(if (rtl) size.width - length else 0f, top), Size(length, h), CornerRadius(radius))
    if (steps > 0) for (index in 0..steps + 1) {
        val point = index.toFloat() / (steps + 1)
        val at = (size.width * point - radius).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
        drawCircle(color.copy(alpha = .4f), 1.dp.toPx(), Offset(if (rtl) size.width - at else at, size.height / 2))
    }
}

/** The containing preference row owns the toggle's semantics and full-width tap target. */
@Composable
internal fun InterfaceSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    val speed = LocalMotionSpeed.current
    val reduced = LocalReducedMotion.current
    val position = remember { androidx.compose.animation.core.Animatable(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var settleRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(checked, reduced, speed, dragging, settleRequest) {
        if (dragging) return@LaunchedEffect
        if (reduced) position.snapTo(if (checked) 1f else 0f)
        else position.animateTo(if (checked) 1f else 0f, spring(1f, 500f * speed * speed))
    }
    var raw by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    var pressure by remember { mutableFloatStateOf(0f) }
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    val body = rememberGlassBody({ position.value }, { pressure }, { if (pressed || dragging) 1f else 0f })
    val active by animateFloatAsState(if (pressed || dragging || position.isRunning) 1f else 0f,
        tween(if (reduced) 0 else 100), label = "Switch lens")
    Box(Modifier.size(60.dp, 32.dp).testTag("settings-switch").then(if (onChange == null) Modifier else Modifier.observeGlassContact { pressed = it }.elasticControlDrag(
        horizontal = true,
        onStart = { dragging = true; scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { position.stop(); raw = position.value } },
        onDelta = { dx ->
            raw += direction * dx / with(density) { 24.dp.toPx() }
            pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { position.snapTo(magneticChoicePosition(raw.coerceIn(0f, 1f))) }
        }, onEnd = { cancelled ->
            pressure = 0f; dragging = false; settleRequest++
            if (!cancelled) onChange(position.value >= .5f)
        }))) {
        GlassControlScene(Modifier.matchParentSize(), horizontalPadding = 0.dp, geometry = {
            val fraction = position.value.coerceIn(0f, 1f)
            val shape = body.value
            val width = 32.dp.toPx() * shape.scaleX()
            val height = (28.dp.toPx() * shape.scaleY()).coerceAtMost(30.dp.toPx())
            val wall = direction * when { fraction <= .001f -> -1f; fraction >= .999f -> 1f; else -> 0f }
            val x = (18.dp + 24.dp * (if (rtl) 1f - fraction else fraction)).toPx() + glassWallTranslation(32.dp.toPx(), shape.scaleX(), wall)
            LensGeometry(androidx.compose.ui.geometry.Rect(x - width / 2f, center.y - height / 2f,
                x + width / 2f, center.y + height / 2f), height / 2f)
        }, body = { body.value }, face = { Color.White.copy(alpha = 1f - active) }) {
            drawRoundRect(androidx.compose.ui.graphics.lerp(neutral, NavigationAccent, position.value.coerceIn(0f, 1f)),
                cornerRadius = CornerRadius(size.height / 2))
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

/** Native directional recognition with an explicit cancellation path shared by value lenses. */
@Composable
internal fun Modifier.elasticControlDrag(horizontal: Boolean, onStart: () -> Unit,
    onDelta: (Float) -> Unit, onEnd: (cancelled: Boolean) -> Unit): Modifier {
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(horizontal) {
        if (horizontal) detectHorizontalDragGestures(onDragStart = { start() },
            onDragEnd = { end(false) }, onDragCancel = { end(true) }) { _, amount -> delta(amount) }
        else detectVerticalDragGestures(onDragStart = { start() },
            onDragEnd = { end(false) }, onDragCancel = { end(true) }) { _, amount -> delta(amount) }
    }
}
