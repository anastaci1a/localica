package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FloatingControlRise = 20.dp
internal fun floatingControlDuration(reducedMotion: Boolean, speed: Float): Int =
    if (reducedMotion) 0 else (400f / speed).roundToInt().coerceAtLeast(0)

/** One live presentation for drawing and hit geometry. Logical dismissal revokes input
 * immediately, even when the caller retains the fading drawing through its exit. */
@Stable
internal class FloatingControlMotion internal constructor(
    private val animation: Animatable<Float, *>,
    private val visible: State<Boolean>,
    private val reduced: State<Boolean>,
) {
    val progress: Float get() = if (reduced.value) { if (visible.value) 1f else 0f } else animation.value
    val alpha: Float get() = progress
    val retained: Boolean get() = visible.value || progress > 0f
    val interactive: Boolean get() = visible.value && progress > 0f
    fun offsetY(distancePx: Float): Float = (1f - progress) * distancePx
}

/** Newly mounted controls rise into view; interrupted exits/reentries keep their contour.
 * The effective policy also covers isolated windows/fixtures without the app provider. */
@Composable
internal fun rememberFloatingControlMotion(visible: Boolean,
    reducedMotion: Boolean = LocalReducedMotion.current): FloatingControlMotion {
    val reduced = rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current)
    val speed = LocalMotionSpeed.current
    val animation = remember { Animatable(0f) }
    val target = rememberUpdatedState(visible)
    val policy = rememberUpdatedState(reduced)
    val motion = remember { FloatingControlMotion(animation, target, policy) }
    val policyObserver = LocalReturnPolicyObserver.current
    if (policyObserver != null) SideEffect { policyObserver("motion visible=$visible effectiveReduced=$reduced") }
    LaunchedEffect(visible, reduced, speed) {
        val destination = if (visible) 1f else 0f
        if (reduced) animation.snapTo(destination)
        else animation.animateTo(destination, tween(floatingControlDuration(false, speed), easing = LinearOutSlowInEasing))
    }
    return motion
}
