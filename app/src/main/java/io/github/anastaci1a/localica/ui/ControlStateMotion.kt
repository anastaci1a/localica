package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.roundToInt

// LocalMotionSpeed is 4/3 at normal speed and 2 for Faster: 300 ms / 200 ms.
// A time-symmetric ease keeps both directions equally legible. This animates only
// presentation; callers must use the current logical state for input and semantics.
internal val ControlStateEasing = CubicBezierEasing(.4f, 0f, .6f, 1f)
internal fun controlStateDuration(reducedMotion: Boolean, speed: Float): Int =
    if (reducedMotion) 0 else (400f / speed).roundToInt().coerceAtLeast(0)

@Composable
private fun <T> controlStateAnimationSpec(reducedMotion: Boolean): TweenSpec<T> =
    tween(controlStateDuration(reducedMotion, LocalMotionSpeed.current), easing = ControlStateEasing)

/** Retargets from the displayed value, including rapid reversals; reduced motion snaps. */
@Composable
internal fun animateControlStateFloat(target: Float, label: String,
    reducedMotion: Boolean = LocalReducedMotion.current): State<Float> {
    val animated = animateFloatAsState(target, controlStateAnimationSpec(reducedMotion), label = label)
    val immediate = rememberUpdatedState(target)
    return if (reducedMotion) immediate else animated
}

/** Inherited ink can change independently; only availability owns this clock. */
@Composable
internal fun controlStateColor(enabled: Boolean, enabledColor: Color, disabledColor: Color, label: String): Color {
    val progress by animateControlStateFloat(if (enabled) 1f else 0f, label)
    return lerp(disabledColor, enabledColor, progress)
}
