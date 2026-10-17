package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.floor

/** Physical host-relative placement, including the fractional remainder exactly once. */
@Composable internal fun Modifier.selectorTrackPlacement(bounds: Rect): Modifier {
    val density = LocalDensity.current
    val x = floor(bounds.left).toInt(); val y = floor(bounds.top).toInt()
    return absoluteOffset { IntOffset(x, y) }.wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
        .requiredSize(with(density) { bounds.width.toDp() },
        with(density) { bounds.height.toDp() }).graphicsLayer {
        translationX = bounds.left - x; translationY = bounds.top - y
    }
}

/** One contact owner for choices and navigation. Preview is non-consuming; movement
 * uses shared parent arbitration, and a stationary hold is an additional pickup path. */
@Composable internal fun Modifier.selectorTrackDrag(horizontal: Boolean, eligible: (Offset) -> Boolean,
    contact: (Boolean) -> Unit, onPreview: (Offset) -> Unit, onStart: () -> Unit,
    onDelta: (Float) -> Unit, option: () -> Int, onEnd: (owned: Boolean, cancelled: Boolean) -> Unit,
    gestureKey: Any? = Unit): Modifier {
    val accepts by rememberUpdatedState(eligible)
    val touch by rememberUpdatedState(contact)
    val preview by rememberUpdatedState(onPreview)
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val currentOption by rememberUpdatedState(option)
    val end by rememberUpdatedState(onEnd)
    val haptics by rememberUpdatedState(rememberControlHaptics())
    val parent = rememberControlScrollContext()
    return readControlScrollContext(parent).pointerInput(horizontal, gestureKey) {
        awaitEachGesture {
            // Finish down in Main: selectable may consume it, but does not own movement.
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!accepts(down.position)) return@awaitEachGesture
            touch(true); preview(down.position)
            var started = false
            var released = false
            var pointer = down.id
            var elapsed = 0L
            var holdEligible = true
            val feedback = ControlDragHaptics(haptics)
            val intent = ControlGestureIntent(viewConfiguration.touchSlop, parent.axes,
                if (horizontal) ControlScrollAxis.Horizontal else ControlScrollAxis.Vertical)
            fun engage(over: Float) {
                started = true; start(); delta(over)
                // Pickup establishes the baseline, rather than emitting two pulses together.
                feedback.engage(currentOption())
            }
            try {
                while (true) {
                    val event = if (!started && holdEligible) withTimeoutOrNull(
                        (viewConfiguration.longPressTimeoutMillis - elapsed).coerceAtLeast(1L)) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    } else awaitPointerEvent(PointerEventPass.Initial)
                    if (event == null) {
                        intent.hold()
                        if (intent.owner == ControlGestureOwner.Control) engage(0f)
                        holdEligible = false
                        continue
                    }
                    val point = event.changes.firstOrNull { it.id == pointer } ?: break
                    if (point.isConsumed) break
                    if (!started && event.changes.count { it.pressed } > 1) {
                        // Cancel selectable too: its tap detector otherwise waits for all ups.
                        event.changes.forEach { it.consume() }
                        break
                    }
                    if (!point.pressed) {
                        val replacement = event.changes.firstOrNull { it.pressed }
                        if (started && replacement != null) {
                            // Preserve the same owned gesture and baseline across fingers.
                            point.consume(); pointer = replacement.id
                            continue
                        }
                        if (started) point.consume()
                        released = true
                        break
                    }
                    if (started) {
                        val movement = point.positionChange()
                        delta(if (horizontal) movement.x else movement.y)
                        feedback.move(currentOption()); point.consume()
                    } else {
                        val movement = point.position - down.position
                        elapsed = point.uptimeMillis - down.uptimeMillis
                        if (movement.getDistance() > viewConfiguration.touchSlop) holdEligible = false
                        when (intent.move(movement.x, movement.y, elapsed)) {
                            ControlGestureOwner.Pending -> Unit
                            ControlGestureOwner.Scroll, ControlGestureOwner.Cancelled -> break
                            ControlGestureOwner.Control -> {
                                val travel = if (horizontal) movement.x else movement.y
                                // Cross-axis excursions alone stay silent, but do not make a
                                // later axial movement inert when no parent has won.
                                if (kotlin.math.abs(travel) > viewConfiguration.touchSlop) {
                                    engage(kotlin.math.sign(travel) *
                                        (kotlin.math.abs(travel) - viewConfiguration.touchSlop))
                                    point.consume()
                                }
                            }
                        }
                        if (!started) {
                            // A parent/custom recognizer may win even without advertised context.
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.firstOrNull { it.id == pointer }?.isConsumed == true) break
                        }
                    }
                }
            } finally {
                feedback.finish(released)
                end(started, !released)
                touch(false)
            }
        }
    }
}
