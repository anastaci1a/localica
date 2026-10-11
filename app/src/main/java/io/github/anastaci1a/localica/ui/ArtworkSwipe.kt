package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal data class ArtworkNeighbor(val visit: String, val visual: PlayerVisual)

@Stable
internal class ArtworkSwipe {
    val offset = Animatable(0f)
    var source by mutableStateOf<PlayerVisual?>(null)
    var destination by mutableStateOf<ArtworkNeighbor?>(null)
    var origin: String? = null
    var previous: ArtworkNeighbor? = null
    var next: ArtworkNeighbor? = null
    var cancelled = false
    var pending by mutableStateOf<String?>(null)
    var handoff by mutableStateOf<String?>(null)
    val active: Boolean get() = source != null
    fun clear() { source = null; destination = null; pending = null }
}

/** The held destination survives asynchronous playback/artwork delivery, including duplicate covers. */
@Composable
internal fun rememberArtworkSwipe(visit: String?, visual: PlayerVisual, previous: ArtworkNeighbor?, next: ArtworkNeighbor?,
    width: Float, enabled: Boolean, settings: InterfaceSettings,
    navigate: ((String, (Boolean) -> Unit) -> Unit)?): Pair<ArtworkSwipe, Modifier> {
    val swipe = remember { ArtworkSwipe() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentVisual by rememberUpdatedState(visual)
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val currentNavigate by rememberUpdatedState(navigate)
    val motion by rememberUpdatedState(settings)
    val stride = width * 1.1f
    val velocityThreshold = with(density) { 650.dp.toPx() }
    val minimumTravel = with(density) { 24.dp.toPx() }
    suspend fun settle(value: Float) {
        if (motion.reducedMotion) swipe.offset.snapTo(value)
        else swipe.offset.animateTo(value, spring(dampingRatio = .88f,
            stiffness = if (motion.fasterMotion) 650f else 380f))
    }
    LaunchedEffect(visit, visual.loading, enabled) {
        if (swipe.pending == visit && visit != null && !visual.loading) {
            swipe.handoff = visit
            swipe.offset.snapTo(0f)
            swipe.clear()
        } else if (swipe.active && ((swipe.origin != visit && swipe.pending != visit) || !enabled && swipe.pending == null)) {
            swipe.cancelled = true
            swipe.offset.snapTo(0f)
            swipe.clear()
        }
    }
    fun finish(velocity: Float) { scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!swipe.cancelled && swipe.pending == null) {
                val amount = swipe.offset.value
                val target = swipe.destination
                val commit = target != null && if (abs(velocity) > velocityThreshold) {
                    abs(amount) > minimumTravel && sign(velocity) == sign(amount)
                } else abs(amount) > width * .24f
                if (commit) {
                    swipe.pending = target!!.visit
                    settle(sign(amount) * stride)
                    if (swipe.pending == target.visit) currentNavigate?.invoke(target.visit) { accepted ->
                        if (!accepted && swipe.pending == target.visit) scope.launch {
                            swipe.pending = null
                            settle(0f)
                            swipe.clear()
                        }
                    }
                } else {
                    settle(0f)
                    swipe.clear()
                }
            }
    } }
    val modifier = Modifier.pointerInput(visit, width, enabled, navigate != null, swipe.pending != null) {
        if (enabled && currentNavigate != null && swipe.pending == null) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var overSlop = 0f
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, distance ->
                    change.consume(); overSlop = distance
                }
                if (start != null) {
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    swipe.source = currentVisual
                    swipe.origin = visit
                    swipe.previous = currentPrevious
                    swipe.next = currentNext
                    swipe.cancelled = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { swipe.offset.stop() }
                    fun drag(change: androidx.compose.ui.input.pointer.PointerInputChange, delta: Float) {
                        change.consume()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!swipe.cancelled && swipe.pending == null) {
                            val raw = swipe.offset.value + delta
                            val destination = if (raw < 0f) swipe.next else swipe.previous
                            swipe.destination = destination
                            val displacement = if (destination != null) raw.coerceIn(-stride, stride)
                                else (swipe.offset.value + delta * .2f).coerceIn(-width * .12f, width * .12f)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { swipe.offset.snapTo(displacement) }
                        }
                    }
                    var released = false
                    try {
                        drag(start, overSlop)
                        released = horizontalDrag(start.id) { change -> drag(change, change.positionChange().x) }
                        if (released) {
                            currentEvent.changes.firstOrNull { it.id == start.id }?.let {
                                velocity.addPosition(it.uptimeMillis, it.position)
                            }
                            finish(velocity.calculateVelocity().x)
                        }
                    } finally {
                        if (!released && swipe.pending == null) {
                            swipe.cancelled = true
                            scope.launch { settle(0f); swipe.clear() }
                        }
                    }
                }
            }
        }
    }
    return swipe to modifier
}
