package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** One slider contact, in the slider's local coordinates. */
internal sealed class SliderContact(val position: Offset) {
    /** Recognised immediately; nothing is published yet. */
    class Down(position: Offset) : SliderContact(position)
    /** A hold or a slide the parent cannot use locks the contact to the slider. */
    class Lock(position: Offset) : SliderContact(position)
    class Move(position: Offset) : SliderContact(position)
    /** Genuine release of a locked contact. */
    class Release(position: Offset) : SliderContact(position)
    /** Genuine release before the contact locked. */
    class Tap(position: Offset) : SliderContact(position)
    /** An unlocked contact went to a parent or was cancelled; nothing was published. */
    class Yield(position: Offset) : SliderContact(position)
    /** A locked contact was cancelled (pointer cancellation, consumed release, disposal). */
    class Cancel(position: Offset) : SliderContact(position)
}

/** The shared slider touch model (#538, #539) for the playback bar and every settings
 * slider: recognise at once, lock on hold or on travel no parent can use, hand travel a
 * parent can use to that parent, and treat an unlocked release as a tap. */
@Composable
internal fun Modifier.sliderContact(enabled: Boolean, onContact: (SliderContact) -> Unit): Modifier {
    val handle by rememberUpdatedState(onContact)
    val parent = rememberControlScrollContext()
    if (!enabled) return this
    return readControlScrollContext(parent).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val intent = ControlGestureIntent(viewConfiguration.touchSlop, parent.directions())
            var owned = false
            var finished = false
            var position = down.position
            var elapsed = 0L
            handle(SliderContact.Down(down.position))
            try {
                while (true) {
                    val event = if (!owned && intent.owner == ControlGestureOwner.Pending) withTimeoutOrNull(
                        (ControlHoldLockMillis - elapsed).coerceAtLeast(1L)) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    } else awaitPointerEvent(PointerEventPass.Initial)
                    if (event == null) {
                        intent.hold()
                        if (intent.owner == ControlGestureOwner.Control) { owned = true; handle(SliderContact.Lock(position)) }
                        continue
                    }
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Compose marks a system cancellation consumed; an ancestor may also take the release.
                    if (point.isConsumed) break
                    if (!owned && event.changes.count { it.pressed } > 1) intent.cancel()
                    position = point.position
                    elapsed = point.uptimeMillis - down.uptimeMillis
                    if (!owned) {
                        if (!point.pressed) {
                            point.consume()
                            finished = true
                            handle(SliderContact.Tap(position))
                            break
                        }
                        val movement = point.position - down.position
                        when (intent.move(movement.x, movement.y, elapsed)) {
                            ControlGestureOwner.Pending -> continue
                            ControlGestureOwner.Scroll, ControlGestureOwner.Cancelled -> break
                            ControlGestureOwner.Control -> { owned = true; handle(SliderContact.Lock(position)) }
                        }
                    }
                    point.consume()
                    if (point.pressed) handle(SliderContact.Move(position))
                    else {
                        finished = true
                        handle(SliderContact.Release(position))
                        break
                    }
                }
            } finally {
                if (!finished) handle(if (owned) SliderContact.Cancel(position) else SliderContact.Yield(position))
            }
        }
    }
}

/** Shown position of a touched slider, as a track fraction. Touch-down eases from the
 * resting value to the finger; a locked contact continues that ease from the value it
 * locked at; a yielded contact eases back with the same ease. */
@Stable
internal class SliderTouchPreview(private val scope: CoroutineScope) {
    private val approach = Animatable(0f)
    var touch by mutableStateOf<Float?>(null)
        private set
    var owned by mutableStateOf(false)
        private set
    private var base by mutableFloatStateOf(0f)
    val active: Boolean get() = touch != null

    fun begin(fraction: Float, ease: AnimationSpec<Float>) {
        touch = fraction
        owned = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { approach.snapTo(0f); approach.animateTo(1f, ease) }
    }

    /** [restingFraction] is the published value's fraction when the contact locks. */
    fun lock(restingFraction: Float) { base = restingFraction; owned = true }

    fun release() { touch = null; owned = false }

    fun yield(ease: AnimationSpec<Float>) {
        owned = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            approach.animateTo(0f, ease)
            touch = null
        }
    }

    /** [current] is the resting fraction while pending and the finger's fraction once locked. */
    fun shown(current: Float): Float {
        val finger = touch ?: return current
        val progress = approach.value
        return if (owned) base + (current - base) * progress else current + (finger - current) * progress
    }
}

@Composable
internal fun rememberSliderTouchPreview(): SliderTouchPreview {
    val scope = rememberCoroutineScope()
    return remember(scope) { SliderTouchPreview(scope) }
}
