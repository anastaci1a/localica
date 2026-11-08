package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/** Input stays on the fixed viewport, not the moving/scaled image layer. */
internal fun Modifier.artworkViewerGestures(state: ArtworkViewerState, scope: CoroutineScope,
    reducedMotion: () -> Boolean, stopSpeed: Float) = pointerInput(state, stopSpeed) {
    awaitEachGesture {
        val down = awaitFirstDown()
        state.stopMotion() // Re-grab freezes the displayed frame before touch slop.
        val generation = state.generation
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(down)
        var trackedPointer = down.id
        var singlePointer = true
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var dragging = false
        var released = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed } || generation != state.generation) break
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                // The pointer adapter handles held release without adding UP to the fit.
                if (singlePointer) event.changes.firstOrNull { it.id == trackedPointer }?.let {
                    velocity.addPointerInputChange(it)
                }
                released = true
                break
            }
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (!dragging) {
                totalPan += pan
                totalZoom *= zoom
                val zoomTravel = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                dragging = zoomTravel > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop
            }
            if (pressed.size == 1) {
                val pointer = pressed.single()
                if (!singlePointer || trackedPointer != pointer.id) velocity.resetTracking()
                trackedPointer = pointer.id
                velocity.addPointerInputChange(pointer)
                singlePointer = true
            } else {
                // Never mistake a changed finger count or pinch velocity for a pan fling.
                velocity.resetTracking()
                singlePointer = false
            }
            if (dragging) {
                state.drag(zoom, pan, event.calculateCentroid(useCurrent = false))
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (true)
        if (released && dragging && singlePointer && generation == state.generation) {
            val speed = velocity.calculateVelocity()
            state.release(Offset(speed.x, speed.y), scope, reducedMotion(), stopSpeed)
        }
    }
}
