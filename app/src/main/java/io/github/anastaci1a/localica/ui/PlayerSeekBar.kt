package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Keep Slider's touch and accessibility behavior; draw a quiet, continuous track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    cancelBounds: Rect? = null, onCancel: () -> Unit = {}, onDrag: (Boolean, Boolean) -> Unit = { _, _ -> },
    liveValue: Float = value) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val thickness by animateDpAsState(if (dragging) 9.dp else 5.dp, tween(if (LocalReducedMotion.current) 0 else 160), label = "Seek thickness")
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cancelRelease by remember { mutableStateOf(false) }
    var wasOver by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    var previewGesture by remember { mutableIntStateOf(0) }
    // A fresh touch or keyboard/accessibility edit must not inherit a cancelled hover.
    val preview = if (previewActive) key(previewGesture) {
        seekCancelPreview(value, liveValue, wasOver)
    } else value
    val view = androidx.compose.ui.platform.LocalView.current
    val target by rememberUpdatedState(cancelBounds)
    val report by rememberUpdatedState(onDrag)
    val cancel by rememberUpdatedState(onCancel)
    val touch = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Cancellation is dispatched to Slider before this outer pointer modifier.
            // Permit a touch commit only after observing a genuine release outside X.
            cancelRelease = true
            wasOver = false
            previewGesture++
            previewActive = true
            report(true, false)
            var released = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) {
                        val over = target?.contains(origin + change.position) == true
                        if (over != wasOver) {
                            view.performHapticFeedback(if (over) android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.CLOCK_TICK)
                            wasOver = over
                        }
                        // A consumed synthetic up is cancellation, not commit permission.
                        cancelRelease = change.pressed || over || change.isConsumed
                        report(change.pressed, over)
                        released = !change.pressed
                        if (released) {
                            if (cancelRelease) cancel()
                            // Keep touch-origin updates distinct from subsequent keyboard/TalkBack edits.
                            awaitPointerEvent(PointerEventPass.Final)
                        }
                    }
                } while (!released && event.changes.any { it.pressed })
            } finally {
                previewActive = false
                report(false, false)
                if (!released) { cancelRelease = true; cancel() }
            }
        }
    }
    Slider(value, onValueChange, modifier.onGloballyPositioned { origin = it.positionInRoot() }.then(touch)
        .onPreviewKeyEvent { cancelRelease = false; false }
        .semantics {
            if (enabled) setProgress { requested ->
                val next = requested.coerceIn(valueRange.start, valueRange.endInclusive)
                if (next == value) false else {
                    // A cancelled touch may not finish Slider's drag recognizer.
                    // Accessibility starts an independent edit, never that old touch.
                    cancelRelease = false
                    onValueChange(next); onValueChangeFinished(); true
                }
            }
        }, enabled,
        valueRange = valueRange, onValueChangeFinished = {
            if (cancelRelease) onCancel() else onValueChangeFinished()
            cancelRelease = false
        },
        interactionSource = interaction,
        thumb = { Box(Modifier.width(0.dp).height(40.dp)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val h = thickness.toPx()
                val fraction = ((preview - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val top = (size.height - h) / 2
                drawRoundRect(Color.White.copy(alpha = .2f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                if (fraction > 0f) drawRoundRect(Color.White.copy(alpha = .7f), Offset(0f, top), Size(size.width * fraction, h), CornerRadius(h / 2))
                if (dragging) drawCircle(Color.White.copy(alpha = .9f), h * .7f, Offset(size.width * fraction, size.height / 2))
            }
        })
}

/** Animate ownership, not the moving endpoints: ordinary finger tracking never trails a tween. */
@Composable
internal fun seekCancelPreview(value: Float, liveValue: Float, overCancel: Boolean): Float {
    val duration = if (LocalReducedMotion.current) 0 else (240 / LocalMotionSpeed.current).toInt()
    val liveWeight by animateFloatAsState(if (overCancel) 1f else 0f,
        tween(duration), label = "Seek cancel preview")
    return value + (liveValue - value) * liveWeight
}
