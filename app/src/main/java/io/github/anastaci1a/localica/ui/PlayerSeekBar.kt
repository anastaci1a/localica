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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Keep Slider's touch, keyboard and accessibility behavior; draw a quiet, continuous track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    cancelBounds: Rect? = null, onCancel: () -> Unit = {}, onDrag: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val thickness by animateDpAsState(if (dragging) 9.dp else 5.dp, tween(if (LocalReducedMotion.current) 0 else 160), label = "Seek thickness")
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pointerActive by remember { mutableStateOf(false) }
    var cancelRelease by remember { mutableStateOf(false) }
    val target by rememberUpdatedState(cancelBounds)
    val report by rememberUpdatedState(onDrag)
    val cancel by rememberUpdatedState(onCancel)
    val touch = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            cancelRelease = false
            pointerActive = true
            report(true, false)
            var released = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) {
                        val over = target?.contains(origin + change.position) == true
                        cancelRelease = over
                        report(change.pressed, over)
                        released = !change.pressed
                        if (released) {
                            if (over) cancel()
                            // Keep touch-origin updates distinct from subsequent keyboard/TalkBack edits.
                            awaitPointerEvent(PointerEventPass.Final)
                        }
                    }
                } while (!released && event.changes.any { it.pressed })
            } finally {
                pointerActive = false
                report(false, false)
                if (!released) { cancelRelease = true; cancel() }
            }
        }
    }
    Slider(value, { next ->
        if (!pointerActive) cancelRelease = false
        onValueChange(next)
    }, modifier.onGloballyPositioned { origin = it.positionInRoot() }.then(touch), enabled,
        valueRange = valueRange, onValueChangeFinished = {
            if (cancelRelease) onCancel() else onValueChangeFinished()
            cancelRelease = false
        },
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
