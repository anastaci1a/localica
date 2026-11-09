package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt

/** One measured physical owner. Readiness, native contact and finite swipe decisions remain here;
 * Components receive lifecycle events and a modifier, never a sampled motion coordinate. */
@Composable internal fun ToastMotionHost(key: Any, viewport: FloatingRowViewport,
    seat: FloatingRowSeatProjection, edgePx: Float, shadowClearancePx: Float, exiting: Boolean,
    enabled: Boolean, readingReady: Boolean, initiallyVisible: Boolean,
    bind: (Any, () -> Rect?) -> (() -> Unit), exited: () -> Unit, ready: (Boolean) -> Unit,
    contactStarted: () -> Boolean, contactRejected: () -> Unit, releasing: (Boolean) -> Unit,
    content: @Composable (Modifier) -> Unit) {
    val clock = ownerClock()
    var size by remember(key) { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var placed by remember(key) { mutableStateOf(false) }
    val bounds = viewport.bounds
    val left = (bounds?.left ?: 0f) + edgePx
    val onExited = rememberUpdatedState(exited)
    val motion = if (size.width > 0 && size.height > 0)
        rememberToastMotion(key, size.width.toFloat(), left, shadowClearancePx, initiallyVisible) {
            if (it == ToastMotionEndpoint.Exited) onExited.value()
        } else null
    val onReady = rememberUpdatedState(ready)
    val textReady = rememberUpdatedState(readingReady)
    val onStart = rememberUpdatedState(contactStarted)
    val onRejected = rememberUpdatedState(contactRejected)
    val onReleasing = rememberUpdatedState(releasing)
    DisposableEffect(motion) {
        val release = motion?.let { owner -> bind(owner) { owner.bodyAt(seat.bottom, size.height.toFloat()) } }
        onDispose { release?.invoke() }
    }
    LaunchedEffect(motion) {
        snapshotFlow { placed && textReady.value && motion?.visibleAtRest == true }
            .collect { onReady.value(it) }
    }
    SideEffect { if (motion != null) { if (exiting) motion.exit() else motion.enter() } }
    val flingSeconds = (LocalUiSpec.current.spec.motion.getValue(MotionProfile.Floating) as MotionProfileSpec).durationMs / 1000f
    val gesture = if (motion == null) Modifier else Modifier.toastContact(motion, enabled, flingSeconds,
        { onStart.value() }, { onRejected.value() }, { onReleasing.value(it) })
    Layout(modifier = Modifier.onGloballyPositioned { origin = it.positionInRoot() }, content = {
        key(key) { content(gesture) }
    }) { children, constraints ->
        val available = ((bounds?.width ?: constraints.maxWidth.toFloat()) - 2 * edgePx).roundToInt().coerceAtLeast(1)
        val body = children.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = minOf(constraints.maxWidth, available)))
        size = IntSize(body.width, body.height)
        layout(constraints.maxWidth, constraints.maxHeight) {
            clock.stamp
            if (motion != null) {
                body.place((motion.leftPx - origin.x).roundToInt(), (seat.bottom - body.height - origin.y).roundToInt())
                val rootBounds = viewport.bounds
                placed = rootBounds != null && rootBounds.width > 0 && rootBounds.height > 0 &&
                    body.width > 0 && body.height > 0 && seat.bottom.isFinite() &&
                    seat.bottom > rootBounds.top && seat.bottom <= rootBounds.bottom
            } else placed = false
        }
    }
}

/** Native slop and the same clamped physical coordinate determine dismissal. A cancelled return
 * retains its contact pause until this owner actually settles; no elapsed-time input decision. */
private fun Modifier.toastContact(motion: ToastMotion, enabled: Boolean, flingSeconds: Float,
    started: () -> Boolean, rejected: () -> Unit, releasing: (Boolean) -> Unit): Modifier =
    pointerInput(motion, enabled, flingSeconds) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!started()) return@awaitEachGesture
        if (!motion.contact()) { rejected(); return@awaitEachGesture }
        var active = true
        var moved = Offset.Zero
        var dragging = false
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, Offset.Zero)
        try {
            var released = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val point = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                val delta = point.position - point.previousPosition
                moved += delta
                tracker.addPosition(point.uptimeMillis, moved)
                if (!dragging && abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y)) {
                    dragging = true
                    motion.dragBy(moved.x)
                } else if (dragging) motion.dragBy(delta.x)
                if (dragging) point.consume()
                if (!point.pressed) { released = true; break }
            } while (true)
            val velocity = if (released && dragging) tracker.calculateVelocity().x else 0f
            val dismiss = released && dragging &&
                motion.displacementFromRestPx + velocity * flingSeconds < -motion.widthPx / 2
            releasing(dismiss)
            motion.release(dismiss, velocity)
            active = false
        } finally {
            if (active) { releasing(false); motion.release(false, 0f) }
        }
    }
}
