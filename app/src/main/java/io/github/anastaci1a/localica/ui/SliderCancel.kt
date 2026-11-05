package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Clearance between a dragged slider and the cancel target below it. */
internal val SliderCancelGap = 16.dp

/** Amount a host must rise so a slider ending at [sliderBottom] clears a target whose
 * resting top is [targetTop], all in root pixels with the host at rest. */
internal fun sliderCancelShift(sliderBottom: Float, targetTop: Float, gap: Float): Float =
    (sliderBottom + gap - targetTop).coerceAtLeast(0f)

/** A menu, popup or settings page that shows the slider (X) cancel target at its bottom
 * and rises temporarily when that target would cover the dragged slider (#539). */
@Stable
internal class SliderCancelHost(private val scope: CoroutineScope) {
    internal val target = SeekCancelTargetState()
    var hovered by mutableStateOf(false)
    /** Upward displacement of the host content, in pixels. */
    internal val shift = Animatable(0f)
    internal var layerOrigin = Offset.Zero
    internal var layerSize = IntSize.Zero
    internal var targetSize = 0f
    internal var bottomInset = 0f
    internal var gap = 0f
    internal var duration = 240

    /** [slider] is the slider's current bounds in root pixels. */
    fun begin(slider: Rect) {
        hovered = false
        target.update(true)
        val restTop = layerOrigin.y + layerSize.height - targetSize - bottomInset
        val needed = sliderCancelShift(slider.bottom + shift.value, restTop, gap)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { move(needed) }
    }

    fun over(pointInRoot: Offset): Boolean = target.bounds?.contains(pointInRoot) == true

    fun end(cancelled: Boolean = hovered) {
        target.update(false, cancelled)
        hovered = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { move(0f) }
    }

    private suspend fun move(to: Float) {
        if (duration == 0) shift.snapTo(to) else shift.animateTo(to, tween(duration, easing = LinearOutSlowInEasing))
    }
}

internal val LocalSliderCancelHost = staticCompositionLocalOf<SliderCancelHost?> { null }

@Composable
internal fun rememberSliderCancelHost(): SliderCancelHost {
    val scope = rememberCoroutineScope()
    return remember(scope) { SliderCancelHost(scope) }
}

/** Full-size, input-transparent layer drawing the host's cancel target. */
@Composable
internal fun SliderCancelLayer(host: SliderCancelHost, bottomInset: Dp) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    SideEffect {
        with(density) {
            host.targetSize = CompactControlSize.roundToPx().toFloat()
            host.bottomInset = bottomInset.toPx()
            host.gap = SliderCancelGap.toPx()
        }
        host.duration = if (reduced) 0 else (240 / speed).toInt()
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        host.layerOrigin = it.positionInRoot()
        host.layerSize = it.size
    }) {
        SeekCancelTarget(host.target, host.hovered, bottomInset, reduced,
            action = "change", tag = "cancel-slider")
    }
}
