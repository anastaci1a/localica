package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The playback bar owns its contact under the shared control intent (#538):
 * touch-down is recognised at once and eases the shown position to the finger;
 * a hold or a horizontal slide locks the scrub, and vertical travel that the player
 * or a scroll parent can use first eases back and hands the swipe over. A tap seeks
 * to the tap point. Only a locked scrub publishes values or shows the cancel target. */
@Composable
internal fun PlayerSeekBar(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    cancelBounds: Rect? = null, onCancel: () -> Unit = {}, onDrag: (Boolean, Boolean) -> Unit = { _, _ -> },
    liveValue: Float = value, cancelTarget: (() -> Rect?)? = null) {
    val fallbackInk = androidx.compose.material3.LocalContentColor.current
    val playerTheme = LocalPlayerDrawTheme.current
    val sink by rememberUpdatedState(rememberControlHaptics())
    val feedback = remember { SeekControlHaptics(ControlHaptics { sink.emit(it) }) }
    val interaction = remember { SeekInteractionSource(MutableInteractionSource(), feedback) }
    val dragging by interaction.collectIsDraggedAsState()
    val reduced = LocalReducedMotion.current
    var touching by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (dragging || touching) 9.dp else 5.dp, tween(if (reduced) 0 else 160), label = "Seek thickness")
    var origin by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableIntStateOf(1) }
    var wasOver by remember { mutableStateOf(false) }
    var previewGesture by remember { mutableIntStateOf(0) }
    // Touch preview in track fractions, so a duration refinement cannot move it.
    val touchPreview = rememberSliderTouchPreview()
    val ease by rememberUpdatedState(seekPreviewEase())
    val current by rememberUpdatedState(value)
    val range by rememberUpdatedState(valueRange)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val target by rememberUpdatedState(cancelBounds)
    val liveTarget by rememberUpdatedState(cancelTarget)
    var pointerInRoot by remember { mutableStateOf<Offset?>(null) }
    val report by rememberUpdatedState(onDrag)
    val cancel by rememberUpdatedState(onCancel)
    var start by remember { mutableStateOf<DragInteraction.Start?>(null) }
    fun fractionOf(v: Float, bounds: ClosedFloatingPointRange<Float> = range): Float {
        val span = bounds.endInclusive - bounds.start
        return if (span <= 0f) 0f else ((v - bounds.start) / span).coerceIn(0f, 1f)
    }
    fun fractionAt(x: Float) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
    fun valueAt(fraction: Float): Float = range.start + fraction.coerceIn(0f, 1f) * (range.endInclusive - range.start)
    fun overAt(point: Offset) = (if (liveTarget != null) liveTarget?.invoke() else target)?.contains(point) == true
    fun publish(point: Offset) {
        val next = valueAt(fractionAt(point.x))
        if (next != current) change(next)
    }
    // Entrance/occupancy can move X across a stationary finger. This observes the
    // same live geometry as event-time release checks.
    LaunchedEffect(Unit) {
        snapshotFlow { pointerInRoot?.let { overAt(it) } }
            .collect { over -> if (over != null && over != wasOver) {
                wasOver = over
                feedback.pointer(over, genuineUp = false, cancelled = false)
                report(true, over)
            } }
    }
    fun end(cancelled: Boolean = false) {
        pointerInRoot = null
        touching = false
        start = null
        report(false, cancelled)
    }
    // The playback bar follows the shared slider touch model (#538, #539).
    val touch = Modifier.sliderContact(enabled) { contact ->
        val point = contact.position
        when (contact) {
            is SliderContact.Down -> {
                feedback.beginTouch()
                wasOver = false
                previewGesture++
                touching = true
                touchPreview.begin(fractionAt(point.x), ease)
            }
            is SliderContact.Lock -> {
                // Continue the touch-down ease from the playback position it started at.
                touchPreview.lock(fractionOf(current))
                report(true, false)
                start = DragInteraction.Start().also { interaction.tryEmit(it) }
                pointerInRoot = origin + point
                publish(point)
            }
            is SliderContact.Move -> {
                val over = overAt(origin + point)
                wasOver = over
                pointerInRoot = origin + point
                feedback.pointer(over, genuineUp = false, cancelled = false)
                report(true, over)
                publish(point)
            }
            is SliderContact.Release -> {
                // Releasing on X cancels; any other genuine release commits.
                val over = overAt(origin + point)
                wasOver = over
                pointerInRoot = null
                feedback.pointer(over, genuineUp = true, cancelled = over)
                if (!over) publish(point)
                start?.let { interaction.tryEmit(DragInteraction.Stop(it)) }
                if (over) cancel() else finished()
                touchPreview.release()
                end(over)
            }
            is SliderContact.Tap -> {
                // A tap seeks to the tap point.
                feedback.pointer(false, genuineUp = true, cancelled = false)
                touchPreview.touch?.let { tapped -> valueAt(tapped).let { if (it != current) change(it) } }
                feedback.materialFinish()
                feedback.pointerFinal()
                finished()
                touchPreview.release()
                end()
            }
            is SliderContact.Yield -> {
                // Unlocked: ease back to playback with the cancel target's ease, publishing nothing.
                feedback.cancelTouch()
                touchPreview.yield(ease)
                end()
            }
            is SliderContact.Cancel -> {
                feedback.cancelTouch()
                start?.let { interaction.tryEmit(DragInteraction.Cancel(it)) }
                cancel()
                touchPreview.release()
                end()
            }
        }
    }
    val shown = touchPreview.shown(fractionOf(value, valueRange))
    // A fresh touch or keyboard/accessibility edit must not inherit a cancelled hover.
    val preview = if (touchPreview.owned) key(previewGesture) {
        seekCancelPreview(shown, fractionOf(liveValue, valueRange), wasOver)
    } else shown
    val semantics = Modifier.semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, 0)
        if (!enabled) disabled()
        setProgress { requested ->
            val next = requested.coerceIn(valueRange.start, valueRange.endInclusive)
            if (!enabled || next == value) false else {
                // Accessibility starts an independent edit, never a cancelled touch.
                feedback.nonTouchInput()
                feedback.activation()
                onValueChange(next); onValueChangeFinished(); true
            }
        }
    }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot(); width = it.size.width }.then(semantics).then(touch)
        .onKeyEvent { event ->
            feedback.nonTouchInput()
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step = when (event.key) {
                Key.DirectionRight, Key.DirectionUp -> .01f
                Key.DirectionLeft, Key.DirectionDown -> -.01f
                Key.PageUp -> .1f
                Key.PageDown -> -.1f
                Key.MoveHome -> -1f
                Key.MoveEnd -> 1f
                else -> return@onKeyEvent false
            }
            val next = (value + step * (valueRange.endInclusive - valueRange.start)).coerceIn(valueRange)
            if (next != value) { feedback.activation(); onValueChange(next); onValueChangeFinished() }
            true
        }.focusable(enabled)) {
        Canvas(Modifier.fillMaxWidth().height(40.dp).align(androidx.compose.ui.Alignment.Center)) {
            val ink = playerTheme?.foreground?.invoke() ?: fallbackInk
            val h = thickness.toPx()
            val top = (size.height - h) / 2
            drawRoundRect(ink.copy(alpha = .2f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            if (preview > 0f) drawRoundRect(ink.copy(alpha = .7f), Offset(0f, top), Size(size.width * preview, h), CornerRadius(h / 2))
            if (dragging || touching) drawCircle(ink.copy(alpha = .9f), h * .7f, Offset(size.width * preview, size.height / 2))
        }
    }
}

/** One ease for entering/leaving X, easing to a touch and easing back from a handed-over touch. */
@Composable
internal fun seekPreviewEase(): AnimationSpec<Float> =
    tween(if (LocalReducedMotion.current) 0 else (240 / LocalMotionSpeed.current).toInt())

/** Animate ownership, not the moving endpoints: ordinary finger tracking never trails a tween. */
@Composable
internal fun seekCancelPreview(value: Float, liveValue: Float, overCancel: Boolean): Float {
    val liveWeight by animateFloatAsState(if (overCancel) 1f else 0f,
        seekPreviewEase(), label = "Seek cancel preview")
    return value + (liveValue - value) * liveWeight
}
