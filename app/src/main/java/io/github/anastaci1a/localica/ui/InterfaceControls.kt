package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import kotlin.math.pow
import kotlin.math.roundToInt

/** Captions share the real slider-track inset and remain visually attached to it. */
@Composable internal fun SliderEndpoints(low: String, high: String) {
    Row(Modifier.fillMaxWidth().offset(y = (-6).dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(low, style = MaterialTheme.typography.bodySmall)
        Text(high, style = MaterialTheme.typography.bodySmall)
    }
}

/** Keep a slider's label, track and captions one layout unit in spaced settings lists. */
@Composable internal fun SliderPreference(label: String, value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    low: String? = null, high: String? = null, description: String? = null, curve: Float = 1f) {
    SliderPreferenceFrame(label, low, high, description) {
        InterfaceSlider(value, onValueChange, modifier, valueRange, steps, enabled = LocalSettingsEnabled.current, curve = curve)
    }
}

/** One coordinate model drives touch, drawing, keyboard and accessible values. */
internal fun controlValue(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val bounded = fraction.coerceIn(0f, 1f)
    val snapped = if (steps > 0) (bounded * (steps + 1)).roundToInt().toFloat() / (steps + 1) else bounded
    return range.start + snapped * (range.endInclusive - range.start)
}

/** Track position of a stored value: position = fraction^(1/curve), so a curve above 1 gives the low end more travel. */
internal fun sliderPosition(value: Float, range: ClosedFloatingPointRange<Float>, curve: Float): Float {
    val fraction = ((value - range.start) / (range.endInclusive - range.start).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    return fraction.pow(1f / curve)
}

/** Stored value at a track position; the inverse of [sliderPosition]. */
internal fun sliderValue(position: Float, range: ClosedFloatingPointRange<Float>, curve: Float): Float =
    range.start + position.coerceIn(0f, 1f).pow(curve) * (range.endInclusive - range.start).coerceAtLeast(.001f)

@Composable
internal fun InterfaceSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true, curve: Float = 1f) {
    val restingOnly = requestedSingleToneBlur()
    val enabledProgress by animateControlStateFloat(if (enabled) 1f else 0f, enabled, "Slider availability")
    val actualSteps = if (steps == 0 && valueRange.start == 0f && valueRange.endInclusive == 1f) 19 else steps
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val haptics by rememberUpdatedState(rememberControlHaptics())
    val host by rememberUpdatedState(LocalSliderCancelHost.current)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Owned finger position; null while resting or pending.
    var finger by remember { mutableStateOf<Float?>(null) }
    var contact by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var pressure by remember { mutableFloatStateOf(0f) }
    var gesture by remember { mutableIntStateOf(0) }
    // The value a cancel bubble restores: the value when the contact locked.
    var original by remember { mutableFloatStateOf(value) }
    var pointerInRoot by remember { mutableStateOf<Offset?>(null) }
    val layout = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val touchPreview = rememberSliderTouchPreview()
    val reduced = LocalReducedMotion.current
    val ease = seekPreviewEase()
    val currentEase by rememberUpdatedState(ease)
    // Hovering X morphs the clear lens back into the resting white circle.
    val active by animateFloatAsState(if (contact && !hovered) 1f else 0f, ease, label = "Slider contact")
    val expansion = LocalGlassAppearance.current.controlExpansion
    val resting = sliderPosition(value, valueRange, curve)
    val touched = touchPreview.shown(finger ?: resting)
    // Entering X eases to the original value; leaving eases back to the finger.
    val hoverShown = if (touchPreview.owned) key(gesture) {
        seekCancelPreview(touched, sliderPosition(original, valueRange, curve), hovered)
    } else touched
    val shown by animateFloatAsState(if (touchPreview.active) hoverShown else resting,
        if (touchPreview.active || reduced) androidx.compose.animation.core.snap() else spring(1f, 650f), label = "Slider position")
    val body = key(restingOnly) {
        if (restingOnly) remember { mutableStateOf(GlassShape()) } else {
            val body = rememberGlassBody({ shown }, { pressure }, { if (contact && !hovered) 1f else 0f })
            body
        }
    }
    // Down-time state for one contact.
    val contactState = remember { floatArrayOf(0f) } // [grab offset]
    var feedback by remember { mutableStateOf<ControlDragHaptics?>(null) }
    fun geometry(): Pair<Float, Float> = with(density) {
        val width = layout[0]?.size?.width?.toFloat() ?: 1f
        14.dp.toPx() to (width - 28.dp.toPx()).coerceAtLeast(1f)
    }
    fun fraction(x: Float): Float {
        val (inset, extent) = geometry()
        val at = (x - inset) / extent
        return if (rtl) 1f - at else at
    }
    fun stepOf(snapped: Float) = if (actualSteps > 0) (snapped * (actualSteps + 1)).roundToInt() else 0
    fun hover(over: Boolean) {
        if (over == hovered) return
        hovered = over
        host?.hovered = over
        haptics.emit(if (over) ControlHaptic.Strong else ControlHaptic.Weak)
        if (over) change(original)
    }
    /** Track the finger; publish its value unless the cancel bubble holds the original. */
    fun track(position: Offset): Int {
        val raw = fraction(position.x) + contactState[0]
        pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
        finger = if (actualSteps > 0) magneticChoicePosition(raw.coerceIn(0f, 1f) * (actualSteps + 1)) / (actualSteps + 1) else raw.coerceIn(0f, 1f)
        val snapped = controlValue(raw, 0f..1f, actualSteps)
        if (!hovered) change(sliderValue(snapped, valueRange, curve))
        return stepOf(snapped)
    }
    fun end(cancelled: Boolean = hovered) {
        if (touchPreview.owned) host?.end(cancelled)
        contact = false; hovered = false; pressure = 0f; finger = null; pointerInRoot = null
        feedback = null
    }
    // A target rising under a stationary finger is entered without movement.
    LaunchedEffect(Unit) {
        snapshotFlow { pointerInRoot?.let { point -> host?.over(point) } }
            .collect { over -> if (over != null) hover(over) }
    }
    val touch = Modifier.sliderContact(enabled) { event ->
        val point = event.position
        when (event) {
            is SliderContact.Down -> {
                gesture++
                hovered = false
                contact = true
                feedback = ControlDragHaptics(haptics)
                val initial = sliderPosition(current, valueRange, curve)
                val at = fraction(point.x)
                // Touching the thumb keeps its offset; anywhere else the thumb comes to the finger.
                val grabbed = kotlin.math.abs(at - initial) * geometry().second <= with(density) { 24.dp.toPx() }
                contactState[0] = if (grabbed) initial - at else 0f
                touchPreview.begin((at + contactState[0]).coerceIn(0f, 1f), currentEase)
            }
            is SliderContact.Lock -> {
                original = current
                touchPreview.lock(sliderPosition(current, valueRange, curve))
                val raw = fraction(point.x) + contactState[0]
                feedback?.engage(stepOf(controlValue(raw, 0f..1f, actualSteps)))
                layout[0]?.takeIf { it.isAttached }?.let { coordinates ->
                    host?.begin(coordinates.boundsInRoot())
                    pointerInRoot = coordinates.localToRoot(point)
                }
            }
            is SliderContact.Move -> {
                layout[0]?.takeIf { it.isAttached }?.let { pointerInRoot = it.localToRoot(point) }
                pointerInRoot?.let { hover(host?.over(it) == true) }
                val step = track(point)
                if (!hovered) feedback?.move(step)
            }
            is SliderContact.Release -> {
                val over = pointerInRoot?.let { host?.over(it) } == true
                if (over) {
                    // Releasing on X restores the original value without a release pulse.
                    if (!hovered) change(original)
                    feedback?.finish(false)
                } else {
                    feedback?.move(track(point))
                    feedback?.finish(true)
                }
                end(over)
                touchPreview.release()
            }
            is SliderContact.Tap -> {
                track(point)
                haptics.emit(ControlHaptic.Weak)
                end()
                touchPreview.release()
            }
            is SliderContact.Yield -> {
                // A parent took the contact: ease back without publishing.
                end()
                touchPreview.yield(currentEase)
            }
            is SliderContact.Cancel -> {
                // A continuous settings slider keeps its last valid value on cancellation.
                feedback?.finish(false)
                end()
                touchPreview.release()
            }
        }
    }
    val semantics = Modifier.semantics {
        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, actualSteps)
        if (!enabled) disabled()
        setProgress { requested ->
            if (enabled) {
                haptics.emit(ControlHaptic.Weak)
                change(sliderValue(controlValue(sliderPosition(requested, valueRange, curve), 0f..1f, actualSteps), valueRange, curve))
            }
            enabled
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(InterfaceSliderHeight).onGloballyPositioned { layout[0] = it }
        .then(semantics).then(touch)
        .onKeyEvent { event ->
            if (!enabled || event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else {
                val direction = when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionRight -> if (rtl) -1 else 1
                    androidx.compose.ui.input.key.Key.DirectionLeft -> if (rtl) 1 else -1
                    androidx.compose.ui.input.key.Key.DirectionUp -> 1
                    androidx.compose.ui.input.key.Key.DirectionDown -> -1
                    else -> 0
                }
                if (direction != 0) {
                    haptics.emit(ControlHaptic.Weak)
                    val stepSize = direction.toFloat() / (if (actualSteps > 0) actualSteps + 1 else 100)
                    change(sliderValue(controlValue(sliderPosition(current, valueRange, curve) + stepSize, 0f..1f, actualSteps), valueRange, curve))
                }
                direction != 0
            }
        }.focusable(enabled)) {
    fun Density.thumbBounds(controlWidth: Float): Rect {
        val scale = if (restingOnly) 1f else 1f + (expansion - 1f) * active
        val baseWidth = 28.dp.toPx() * scale
        val width = baseWidth * body.value.scaleX()
        val height = 28.dp.toPx() * (1f + (scale - 1f) * 2f / 9f) * body.value.scaleY()
        val fraction = if (rtl) 1f - shown else shown
        val x = 14.dp.toPx() + (controlWidth - 28.dp.toPx()) * fraction +
            glassWallTranslation(baseWidth, body.value.scaleX(), glassWallAffinity(fraction, 1f))
        return Rect(x - width / 2, (48.dp.toPx() - height) / 2,
            x + width / 2, (48.dp.toPx() + height) / 2)
    }
    GlassControlScene(Modifier.matchParentSize().testTag("settings-slider-scene"), horizontalPadding = 32.dp,
        geometry = { thumbBounds(size.width).let { LensGeometry(it, it.height / 2) } },
        body = { body.value }, active = { active }, face = { Color.White.copy(alpha = 1f - active) }) {
        inset(14.dp.toPx(), 0f) {
            drawSliderTrack(shown, actualSteps, enabled, rtl, NavigationAccent, active, enabledProgress)
        }
    }
    // Keep test/accessibility geometry aligned without composing the entire control
    // on every contact, position and rebound frame.
    Box(Modifier.offset {
        thumbBounds(maxWidth.toPx()).let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) }
    }.layout { measurable, _ ->
        val rect = thumbBounds(maxWidth.toPx())
        val item = measurable.measure(Constraints.fixed(rect.width.roundToInt(), rect.height.roundToInt()))
        layout(item.width, item.height) { item.place(0, 0) }
    }.testTag("settings-slider-thumb"))
    }
}

/** Stop markers live inside rounded caps, including the current active endpoint. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSliderTrack(fraction: Float, steps: Int,
    enabled: Boolean, rtl: Boolean, color: Color, dots: Float = 1f,
    enabledProgress: Float = if (enabled) 1f else 0f) {
    val h = 5.dp.toPx(); val radius = h / 2; val top = (size.height - h) / 2
    drawRoundRect(color.copy(alpha = .08f + .08f * enabledProgress), Offset(0f, top), Size(size.width, h), CornerRadius(radius))
    val length = size.width * fraction
    if (fraction > 0f) drawRoundRect(color.copy(alpha = .3f + .5f * enabledProgress),
        Offset(if (rtl) size.width - length else 0f, top), Size(length, h), CornerRadius(radius))
    if (steps > 0) for (index in 0..steps + 1) {
        val point = index.toFloat() / (steps + 1)
        val at = (size.width * point - radius).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
        drawCircle(color.copy(alpha = .4f * dots), 1.dp.toPx(), Offset(if (rtl) size.width - at else at, size.height / 2))
    }
}

/** The containing preference row owns the toggle's semantics and full-width tap target. */
@Composable
internal fun InterfaceSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    val restingOnly = requestedSingleToneBlur()
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
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    // A tap's destination is already committed before the settling effect starts.
    // Keep the same optical body through direct contact and automatic travel:
    // releasing into a thick neutral lens would sample only the opaque track.
    val engaged by remember(checked, reduced) { derivedStateOf {
        pressed || dragging || (!reduced && (position.isRunning || position.value != if (checked) 1f else 0f))
    } }
    val source = LocalFrostedBackdrop.current
    val ready = flatGlass() || source == null || if (android.os.Build.VERSION.SDK_INT >= 33)
        source.recorded && !source.layer.isReleased else source.snapshot != null
    // Start the lease at commitment, but retain the opaque resting face until
    // the first real recording is sampleable. Logical travel never waits for it.
    val materialEngaged = engaged && ready
    val active by animateFloatAsState(if (materialEngaged) 1f else 0f,
        tween(if (reduced) 0 else if (materialEngaged) (80f / speed).toInt().coerceAtLeast(1) else 180,
            easing = if (materialEngaged) androidx.compose.animation.core.LinearOutSlowInEasing
                else androidx.compose.animation.core.FastOutSlowInEasing), label = "Switch lens")
    // A replaced/unready source cannot become a track-only lens during exit.
    val availableActive by remember(ready) { derivedStateOf { if (ready) active else 0f } }
    val body = key(restingOnly) {
        if (restingOnly) remember { mutableStateOf(GlassShape()) } else {
            val body = rememberGlassBody({ position.value }, { pressure }, { if (engaged) 1f else active })
            body
        }
    }
    val expansion = LocalGlassAppearance.current.controlExpansion
    val change by rememberUpdatedState(onChange)
    val haptics by rememberUpdatedState(rememberControlHaptics())
    val parent = rememberControlScrollContext()
    Box(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight).testTag("settings-switch").then(if (onChange == null) Modifier else Modifier.readControlScrollContext(parent).pointerInput(rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val intent = ControlGestureIntent(viewConfiguration.touchSlop, parent.directions())
            pressed = true
            val initial = position.value
            var moved = false
            var released = false
            val feedback = ControlDragHaptics(haptics)
            var feedbackEngaged = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (point.isConsumed) break
                    if (event.changes.count { it.pressed } > 1) {
                        event.changes.forEach { it.consume() }
                        break
                    }
                    val movement = point.position - down.position
                    if (!dragging) {
                        when (intent.move(movement.x, movement.y, point.uptimeMillis - down.uptimeMillis)) {
                            ControlGestureOwner.Scroll, ControlGestureOwner.Cancelled -> break
                            ControlGestureOwner.Pending -> if (point.pressed) continue
                            ControlGestureOwner.Control -> Unit
                        }
                        dragging = true
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { position.stop() }
                    }
                    val dx = point.position.x - down.position.x
                    moved = moved || kotlin.math.abs(dx) > viewConfiguration.touchSlop
                    raw = initial + direction * dx / 28.dp.toPx()
                    pressure = glassEdgePressure(raw - raw.coerceIn(0f, 1f))
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { position.snapTo(magneticChoicePosition(raw.coerceIn(0f, 1f))) }
                    val option = if (position.value >= .5f) 1 else 0
                    if (point.pressed) {
                        feedback.engage(option)
                        feedbackEngaged = true
                        feedback.move(option)
                    } else if (!feedbackEngaged) haptics.emit(ControlHaptic.Weak)
                    else feedback.move(option)
                    point.consume()
                    if (!point.pressed) { released = true; break }
                }
            } finally {
                feedback.finish(released)
                pressure = 0f; pressed = false; dragging = false; settleRequest++
                if (released) change?.invoke(if (moved) position.value >= .5f else initial < .5f)
            }
        }
    })) {
        GlassControlScene(Modifier.matchParentSize(), horizontalPadding = 24.dp, source = source, prepare = { engaged }, geometry = {
            val fraction = position.value.coerceIn(0f, 1f)
            val shape = body.value
            val scale = if (restingOnly) 1f else 1f + (expansion - 1f) * availableActive
            val width = 28.dp.toPx() * scale * shape.scaleX()
            val height = 28.dp.toPx() * scale * shape.scaleY()
            val wall = direction * glassWallAffinity(fraction, 1f)
            val x = (16.dp + 28.dp * (if (rtl) 1f - fraction else fraction)).toPx() + glassWallTranslation(28.dp.toPx() * scale, shape.scaleX(), wall)
            LensGeometry(androidx.compose.ui.geometry.Rect(x - width / 2f, center.y - height / 2f,
                x + width / 2f, center.y + height / 2f), height / 2f)
        }, body = { body.value }, active = { availableActive }, face = { Color.White.copy(alpha = 1f - availableActive) }) {
            drawRoundRect(androidx.compose.ui.graphics.lerp(neutral, NavigationAccent, position.value.coerceIn(0f, 1f)),
                topLeft = Offset(0f, center.y - 16.dp.toPx()), size = Size(size.width, 32.dp.toPx()), cornerRadius = CornerRadius(16.dp.toPx()))
        }
    }
}

@Composable
internal fun InterfaceCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val progress by animateControlStateFloat(if (checked) 1f else 0f, checked, "Checkmark")
    val opacity by animateControlStateFloat(if (enabled) 1f else .38f, enabled, "Checkbox availability")
    val foreground = LocalContentColor.current
    Box(modifier.size(48.dp).then(if (onCheckedChange != null)
        Modifier.ownButtonDrag(enabled).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier)
        .graphicsLayer { alpha = opacity }, contentAlignment = Alignment.Center) {
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
    val opacity by animateControlStateFloat(if (enabled) 1f else .38f, enabled, "Filter availability")
    val selection by animateControlStateFloat(if (checked) .09f else 0f, checked, "Filter selection")
    Box(Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)) {
        LiquidGlassSurface(Modifier.height(40.dp).ownButtonDrag(enabled).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { selected = checked }.glassShadowOpacity { opacity },
            tint = Color.Black.copy(alpha = selection), pressFeedback = enabled) {
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
